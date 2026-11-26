// 每个迭代独立派生伪随机数，发压调度变化时仍可按迭代编号重放选择。
export function randomFor(seed, iteration, vu) {
  let state = (seed ^ Math.imul(iteration + 1, 0x9e3779b1) ^ Math.imul(vu, 0x85ebca6b)) >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

export function choose(values, random) {
  return values[Math.floor(random() * values.length)];
}

// VU ID 在本进程中从 1 起，每个编号只读取自己的会话槽。
export function sessionFor(sessions, vu) {
  return sessions[vu - 1];
}

export function distributedArticle(partitions, iteration, random) {
  const group = partitions[iteration % partitions.length];
  return group.first + group.step * Math.floor(random() * group.count);
}

export function detailArticle(count, random) {
  const hot = Math.max(1, Math.floor(count / 100));
  if (random() < 0.6) return 1 + Math.floor(random() * hot);
  return hot + 1 + Math.floor(random() * (count - hot));
}

export function searchPath(query, cursor, size = 5) {
  const args = ['size=' + size];
  if (query.q) args.push('q=' + encodeURIComponent(query.q));
  if (query.tagId) args.push('tagId=' + query.tagId);
  if (cursor) args.push('cursor=' + encodeURIComponent(cursor));
  return '/api/articles/search?' + args.join('&');
}

export function validSearch(body, query, previous = [], size = 5) {
  if (!body || !Array.isArray(body.items) || body.items.length > size ||
      (body.nextCursor !== null && typeof body.nextCursor !== 'string')) return false;
  const ids = body.items.map(item => item.articleId);
  if (new Set(ids).size !== ids.length || ids.some(id => previous.includes(id))) return false;
  if (query.empty) return ids.length === 0;
  return ids.length > 0 &&
    (!query.articleId || ids.includes(String(query.articleId))) &&
    body.items.every(item => typeof item.articleId === 'string' &&
      (!query.tagId || item.tags?.some(tag => tag.tagId === String(query.tagId))));
}

export function validDetail(body, id) {
  return body?.articleId === String(id) && typeof body.contentHtml === 'string' &&
    body.contentHtml.length > 0 && typeof body.likedByMe === 'boolean' &&
    Number.isSafeInteger(body.likeCount) && body.likeCount >= 0 &&
    Number.isSafeInteger(body.viewCount) && body.viewCount >= 0;
}

export function validLike(body, operation, liked) {
  return body?.accepted === true && body.operationId === operation && body.liked === liked &&
    typeof body.version === 'string' && /^[1-9][0-9]{0,18}$/.test(body.version);
}
