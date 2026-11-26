import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import {
  choose, detailArticle, distributedArticle, randomFor, searchPath, sessionFor, utf8Bytes, validDetail, validLike, validSearch,
} from './workload.mjs';
import {
  commentListPath, commentPayload, commentReadArticle, commentSubmissionPath, commentWriteArticle,
  validCommentPage, validReplySubmission, validRootSubmission,
} from './comments.mjs';

const config = JSON.parse(open(__ENV.EC_BASELINE_INPUT));
const scenario = __ENV.EC_BASELINE_SCENARIO;
const duration = __ENV.EC_BASELINE_DURATION + 's';
const load = Number(__ENV.EC_BASELINE_LOAD);
const vus = Number(__ENV.EC_BASELINE_VUS);
const closed = __ENV.EC_BASELINE_MODEL === 'VU';
const journey = new Rate('journey_completed');
const journeyStarted = new Counter('journey_started');

export const options = {
  scenarios: {
    [scenario]: closed ?
      { executor: 'constant-vus', vus, duration, gracefulStop: '12s' } :
      { executor: 'constant-arrival-rate', rate: load, timeUnit: '1s', duration,
        preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '12s' },
  },
  // 稳定的路由标签避免把文章、搜索词和游标写入指标标签。
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'check', 'error_code'],
};

let pendingPage;
const lastLikes = new Map();
let operationCount = 0;

function send(name, method, path, session, validator, details = {}) {
  const headers = session ? { Cookie: session.cookie } : {};
  if (details.payload) {
    headers['Content-Type'] = 'application/json';
    headers[session.csrfHeader] = session.csrf;
  }
  if (details.operation) {
    headers[session.csrfHeader] = session.csrf;
    headers['X-Operation-Id'] = details.operation;
  }
  const start = Date.now();
  const response = http.request(method, config.base + path, details.payload ? JSON.stringify(details.payload) : null, {
    headers, redirects: 0, tags: { name }, timeout: details.timeout ?? '4s',
  });
  const end = Date.now();
  let body;
  try { body = response.json(); } catch (_) { /* 网络错误和非 JSON 响应由检查统一记录。 */ }
  const expected = details.statuses ?? (details.operation ? [202] : [200]);
  const ok = expected.includes(response.status) && validator(body, response.status);
  const outcome = details.outcome?.(body, ok) ?? null;
  check(ok, { '业务响应有效': value => value }, { name });
  // 日志只保存测试标识、状态与时间，省略响应正文、Cookie 和 CSRF。
  console.log('EC_HTTP ' + JSON.stringify({
    name, start, end, status: response.status, ok, user: session?.user ?? 0,
    article: details.article ?? 0, liked: details.liked ?? false,
    version: ok && details.operation ? body.version : null,
    bytes: typeof response.body === 'string' ? utf8Bytes(response.body) : 0,
    variant: details.variant ?? '',
    ...(details.submission ? { submission: details.submission, sample: details.sample ?? false, outcome } : {}),
  }));
  return { ok, body, start, outcome };
}

function search(query, session, cursor, previous) {
  const result = send(cursor ? 'search-page' : 'search-first', 'GET', searchPath(query, cursor, config.pageSize), session,
    body => validSearch(body, query, previous, config.pageSize), { variant: query.kind });
  return result.ok ? result.body : null;
}

function detail(id, session) {
  return send('detail', 'GET', '/api/articles/' + id, session, body => validDetail(body, id), { article: id });
}

function operationId() {
  // UUID 的两段分别使用 VU 和操作序号，跨 VU 与迭代均不重复。
  return __ENV.EC_BASELINE_OP + '-0000-4000-' + (0x8000 + exec.vu.idInTest).toString(16) +
    '-' + (++operationCount).toString(16).padStart(12, '0');
}

function like(id, session, startingLike, random) {
  const initial = startingLike ?? (random() < 0.5);
  const liked = !(lastLikes.get(id) ?? initial);
  const operation = operationId();
  const result = send('like', liked ? 'PUT' : 'DELETE', '/api/articles/' + id + '/like', session,
    body => validLike(body, operation, liked), { article: id, liked, operation });
  if (result.ok) lastLikes.set(id, liked);
  return result.ok;
}

function observeComment(article, submission, session, commentId, submittedAt) {
  const deadline = submittedAt + 10000;
  while (Date.now() + 650 < deadline) {
    sleep(0.65);
    const remaining = deadline - Date.now();
    if (remaining <= 0) return;
    const details = { article, submission, timeout: Math.min(4000, remaining) + 'ms' };
    const latest = send('comment-visible', 'GET', commentListPath(article, 'latest', 50), session,
      body => validCommentPage(body, 50, false),
      { ...details, outcome: (body, ok) => ok && body.items.some(item => item.id === commentId) ? 'VISIBLE' : null });
    if (latest.outcome === 'VISIBLE') return;
  }
}

function submitComment(article, session, iteration) {
  const submission = operationId();
  const payload = commentPayload(submission);
  // PUT 已确认落库，每五秒只安排一个通过正式列表观察索引可见性的样本。
  const sample = __ENV.EC_BASELINE_PHASE === 'measure' && iteration % (load * 5) === 0;
  const result = send('comment-submit', 'PUT', commentSubmissionPath(article, submission), session,
    body => validRootSubmission(body, session.user, payload.content), { article, submission, payload, sample });
  if (result.ok && sample) observeComment(article, submission, session, result.body.comment.id, result.start);
}

function submitReply(session, iteration) {
  const article = commentWriteArticle(1, config.commentWriteArticles, iteration, scenario === 'comment-reply-write-hot');
  // 种子先连续分配所有一级 ID。每篇选第一楼，分散与热点使用相同写入形状。
  const rootId = String((article - 1) * config.commentRootsPerArticle + 1);
  const submission = operationId();
  const payload = commentPayload(submission, rootId);
  send('comment-reply-submit', 'PUT', commentSubmissionPath(article, submission), session,
    body => validReplySubmission(body, rootId, session.user, payload.content), { article, submission, payload });
}

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const vu = exec.vu.idInTest;
  const random = randomFor(config.seed, iteration, vu);
  const session = sessionFor(config.sessions, vu);
  if (scenario === 'search') {
    // 首屏给本 VU 留一个游标，下一迭代发翻页，固定到达率仍是一迭代一请求。
    if (pendingPage && Date.now() - pendingPage.time < 30000) {
      search(pendingPage.query, null, pendingPage.cursor, pendingPage.ids);
      pendingPage = null;
      return;
    }
    pendingPage = null;
    const query = choose(config.queries, random);
    const page = search(query);
    if (query.page && page?.nextCursor) {
      pendingPage = { query, cursor: page.nextCursor, ids: page.items.map(item => item.articleId), time: Date.now() };
    }
    return;
  }
  if (scenario === 'detail' || scenario === 'detail-cold') {
    detail(detailArticle(config.articles, random), random() < 0.7 ? session : null);
    return;
  }
  if (scenario === 'comment-read') {
    const article = commentReadArticle(config.commentReadArticles, random);
    send('comment-first', 'GET', commentListPath(article, 'hot', 20), random() < 0.7 ? session : null,
      body => validCommentPage(body, 20, true), { article });
    return;
  }
  if (scenario === 'comment-write-spread' || scenario === 'comment-write-hot') {
    const article = commentWriteArticle(config.commentWriteFirst, config.commentWriteArticles, iteration,
      scenario === 'comment-write-hot');
    submitComment(article, session, iteration);
    return;
  }
  if (scenario === 'comment-reply-write-spread' || scenario === 'comment-reply-write-hot') {
    submitReply(session, iteration);
    return;
  }
  if (scenario === 'mixed') {
    journeyStarted.add(1);
    // 开放模型动态复用 VU，按旅程抽取游客，闭合模型仍沿用固定的游客 VU。
    const guest = closed ? vu <= Math.round(vus * 0.3) : random() < 0.3;
    const identity = guest ? null : session;
    const query = choose(config.queries.filter(item => !item.empty), random);
    const page = search(query, identity);
    if (!page) { journey.add(false); return; }
    const id = Number(choose(page.items, random).articleId);
    const result = detail(id, identity);
    sleep(1 + 2 * random());
    journey.add(result.ok && (!identity || random() >= 0.2 || like(id, identity, result.body.likedByMe, random)));
    return;
  }
  const id = scenario === 'like-spread' ? distributedArticle(config.partitions, iteration, random) : 1;
  like(id, session, null, random);
  if (scenario === 'like-probe') sleep(0.4);
}
