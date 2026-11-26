import assert from 'node:assert/strict';
import test from 'node:test';
import { distributedArticle, randomFor, searchPath, sessionFor, validDetail, validLike, validSearch } from './workload.mjs';
import {
  commentListPath, commentPayload, commentReadArticle, commentSubmissionPath, commentWriteArticle,
  validCommentPage, validReplySubmission, validRootSubmission,
} from './comments.mjs';

test('固定种子可重放，并分散到各点赞分区', () => {
  const partitions = [4, 1, 2, 3].map(first => ({ first, step: 4, count: 2 }));
  const run = () => Array.from({ length: 20 }, (_, i) => distributedArticle(partitions, i, randomFor(3, i, 1)));
  assert.deepEqual(run(), run());
  assert.deepEqual(run().slice(0, 4).map(id => id % 4), [0, 1, 2, 3]);
});

test('搜索检查游标重复、固定查询与标签', () => {
  const item = { articleId: '7', tags: [{ tagId: '2' }] };
  const page = { items: [item], nextCursor: 'next+' };
  assert.equal(validSearch(page, { tagId: 2 }), true);
  assert.equal(validSearch(page, { tagId: 3 }), false);
  assert.equal(validSearch(page, {}, ['7']), false);
  assert.equal(validSearch(page, { articleId: 8 }), false);
  assert.equal(validSearch({ items: [], nextCursor: null }, { empty: true }), true);
  assert.equal(searchPath({ q: '编程', tagId: 2 }, 'next+'), '/api/articles/search?size=5&q=%E7%BC%96%E7%A8%8B&tagId=2&cursor=next%2B');
});

test('详情按业务字段判断，点赞版本始终按字符串传递', () => {
  const detail = { articleId: '1', contentHtml: '<p>正文</p>', likedByMe: false, likeCount: 2, viewCount: 0 };
  assert.equal(validDetail(detail, 1), true);
  assert.equal(validDetail(detail, 2), false);
  const response = { accepted: true, operationId: 'op', liked: true, version: '9007199254740993' };
  assert.equal(validLike(response, 'op', true), true);
  assert.equal(validLike({ ...response, version: Number(response.version) }, 'op', true), false);
  assert.equal(validLike(response, 'other', true), false);
});

test('每个 VU 只读自己的会话', () => {
  const sessions = [{ user: 1 }, { user: 2 }];
  assert.notEqual(sessionFor(sessions, 1), sessionFor(sessions, 2));
  assert.equal(sessionFor(sessions, 3), undefined);
});

test('评论读写使用不同目标池，分散写按四个分区轮转', () => {
  const values = [0.59, 0.2, 0.6, 0.2];
  assert.equal(commentReadArticle(100, () => values.shift()), 3);
  assert.equal(commentReadArticle(100, () => values.shift()), 29);
  const spread = Array.from({ length: 8 }, (_, i) => commentWriteArticle(33, 32, i, false));
  assert.deepEqual(spread.map(id => id % 4), [1, 2, 3, 0, 1, 2, 3, 0]);
  assert.equal(commentWriteArticle(33, 32, 7, true), 33);
  assert.equal(commentListPath(33, 'hot', 20), '/api/articles/33/comments?sort=hot&size=20');
  assert.equal(commentSubmissionPath(33, 'op'), '/api/articles/33/comments/submissions/op');
  assert.deepEqual(commentPayload('op'), { content: '基线评论 op', replyToId: null });
});

test('评论首页校验排序、游标和回复归属，发布成功携带一级评论', () => {
  const root = id => ({
    id, rootId: null, author: { id: '1' }, content: '基线评论', status: 'ACTIVE',
    likeCount: 10, likedByMe: false,
    replies: { items: [{ id: '10', rootId: id }, { id: '11', rootId: id }], hasMore: true, nextCursor: 'replies' },
  });
  const first = root('9007199254740993');
  const second = root('9007199254740992');
  const body = { items: [first, second], hasMore: true, nextCursor: 'next', rankVersion: 'rank', commentCount: 32 };
  assert.equal(validCommentPage(body, 2, true), true);
  assert.equal(validCommentPage({ ...body, items: [first, first] }, 2, true), false);
  assert.equal(validCommentPage({ ...body, items: [second, first] }, 2, true), false);
  assert.equal(validCommentPage({ ...body, nextCursor: null }, 2, true), false);
  assert.equal(validCommentPage({ ...body, items: [first, { ...second, replies: first.replies }] }, 2, true), false);
  assert.equal(validCommentPage({ items: [], hasMore: false, nextCursor: null, commentCount: 0 }, 50, false), true);
  const response = { state: 'PUBLISHED', comment: first };
  assert.equal(validRootSubmission(response, 1, first.content), true);
  assert.equal(validRootSubmission(response, 2, first.content), false);
  assert.equal(validRootSubmission({ ...response, commentCount: 1 }, 1, first.content), false);
  assert.equal(validRootSubmission({ state: 'FAILED' }, 1, first.content), false);
});

test('二级写只有返回已提交且归属正确的回复才计为成功', () => {
  const { content, replyToId } = commentPayload('op', '23');
  const comment = { id: '50', rootId: '23', author: { id: '1' }, content, status: 'ACTIVE' };
  const response = { state: 'PUBLISHED', comment };
  assert.equal(validReplySubmission(response, replyToId, 1, content), true);
  assert.equal(validReplySubmission(response, '24', 1, content), false);
  assert.equal(validReplySubmission(response, replyToId, 2, content), false);
  assert.equal(validReplySubmission({ state: 'FAILED' }, replyToId, 1, content), false);
  assert.equal(validReplySubmission({ ...response, commentCount: 32 }, replyToId, 1, content), false);
});
