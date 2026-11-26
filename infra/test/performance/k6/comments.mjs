const decimalId = /^[1-9][0-9]*$/;

export function commentReadArticle(count, random) {
  const hot = Math.max(1, Math.floor(count / 10));
  if (random() < 0.6) return 1 + Math.floor(random() * hot);
  return hot + 1 + Math.floor(random() * (count - hot));
}

export function commentWriteArticle(first, count, iteration, hot) {
  // 连续 ID 在四个计数分片间轮转，低速档也会触达全部分片。
  return first + (hot ? 0 : iteration % count);
}

export function commentListPath(article, sort, size) {
  return '/api/articles/' + article + '/comments?sort=' + sort + '&size=' + size;
}

export function commentSubmissionPath(article, submission) {
  return '/api/articles/' + article + '/comments/submissions/' + submission;
}

export function commentPayload(submission, replyToId = null) {
  return { content: '基线评论 ' + submission, replyToId };
}

function validId(value) {
  return typeof value === 'string' && decimalId.test(value);
}

function validRoot(item) {
  return validId(item?.id) && item.rootId === null && item.replyToUsername === undefined &&
    validId(item.author?.id) && typeof item.content === 'string' && item.content.length > 0 &&
    item.status === 'ACTIVE' && Number.isSafeInteger(item.likeCount) && item.likeCount >= 0 &&
    typeof item.likedByMe === 'boolean';
}

export function validCommentPage(body, size) {
  // 热门首页的种子有超过一页的一级评论，并且每条带有回复预览。
  if (!Array.isArray(body?.items) || body.items.length !== size ||
      !Number.isSafeInteger(body.commentCount) || body.commentCount < size ||
      body.hasMore !== true || typeof body.nextCursor !== 'string' || !body.nextCursor ||
      typeof body.rankVersion !== 'string' || !body.rankVersion) return false;
  if (!body.items.every(validRoot) || new Set(body.items.map(item => item.id)).size !== body.items.length) return false;
  return body.items.every((item, index) => {
    const prior = body.items[index - 1];
    const preview = item.replies;
    // 同赞数按 ID 降序，比较十进制字符串避免丢失 64 位 ID 精度。
    const ordered = !prior || prior.likeCount > item.likeCount || prior.likeCount === item.likeCount &&
      (prior.id.length !== item.id.length ? prior.id.length > item.id.length : prior.id > item.id);
    return ordered && preview?.items?.length === 2 && preview.hasMore === true &&
      typeof preview.nextCursor === 'string' && preview.items.every(reply => validId(reply.id) && reply.rootId === item.id);
  });
}

/** 一级发布成功时正文已落库，异步计数由读接口提供。 */
export function validRootSubmission(body, authorId, content) {
  const item = body?.comment;
  return body?.state === 'PUBLISHED' && body.commentCount === undefined && validRoot(item) &&
    item.author.id === String(authorId) && item.content === content;
}

/** 回复楼主的发布响应须携带可立即展示的回复，一级计数保持独立。 */
export function validReplySubmission(body, rootId, authorId, content) {
  const item = body?.comment;
  return body?.state === 'PUBLISHED' && body.commentCount === undefined &&
    validId(item?.id) && item.rootId === rootId && item.author?.id === String(authorId) &&
    item.content === content && item.status === 'ACTIVE' && item.replyToUsername === undefined;
}
