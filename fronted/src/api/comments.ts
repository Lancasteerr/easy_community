import { request } from './client';
import type { ArticleLikeAccepted, CsrfToken } from './types';

export type CommentSort = 'hot' | 'latest';

/** 回复保持在所属楼层，展示时可附带实际回复对象的账号名。 */
export interface CommentItem {
  id: string;
  rootId: string | null;
  replyToUsername?: string;
  author: { id: string; username: string; avatarUrl?: string };
  content: string;
  createdAt: string;
  likeCount?: number;
  likedByMe?: boolean;
  replies?: CommentPage;
}

export interface CommentPage {
  items: CommentItem[];
  nextCursor: string | null;
  hasMore: boolean;
  commentCount?: number;
}

/** 浏览器只处理发布结果，提交编号在第一次发送前生成。 */
export interface CommentSubmission {
  state: 'PUBLISHING' | 'PUBLISHED' | 'FAILED';
  commentCount?: number;
  comment?: CommentItem;
}

function commentsPath(articleId: string) {
  return `/api/articles/${encodeURIComponent(articleId)}/comments`;
}

export function getComments(articleId: string, sort: CommentSort, cursor: string | null, signal: AbortSignal) {
  const query = new URLSearchParams({ sort });
  if (cursor !== null) query.set('cursor', cursor);
  return request<CommentPage>(`${commentsPath(articleId)}?${query}`, { signal });
}

export function getReplies(articleId: string, rootId: string, cursor: string | null, signal: AbortSignal) {
  const path = `${commentsPath(articleId)}/${encodeURIComponent(rootId)}/replies`;
  const query = new URLSearchParams();
  if (cursor !== null) query.set('cursor', cursor);
  return request<CommentPage>(query.size ? `${path}?${query}` : path, { signal });
}

export function submitComment(articleId: string, id: string, content: string, replyToId: string | null,
  csrf: CsrfToken, signal: AbortSignal) {
  return request<CommentSubmission>(`${commentsPath(articleId)}/submissions/${id}`, {
    method: 'PUT', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ content, replyToId }),
  });
}

export function getCommentSubmission(articleId: string, id: string, signal: AbortSignal) {
  return request<CommentSubmission>(`${commentsPath(articleId)}/submissions/${id}`, { signal });
}

/** 请求层只提取可用于更新点赞按钮的受理字段。 */
export async function setCommentLike(articleId: string, commentId: string, liked: boolean, operationId: string,
  csrf: CsrfToken, signal: AbortSignal) {
  const accepted = await request<ArticleLikeAccepted>(`${commentsPath(articleId)}/${encodeURIComponent(commentId)}/like`, {
    method: liked ? 'PUT' : 'DELETE', signal,
    headers: { [csrf.headerName]: csrf.token, 'X-Operation-Id': operationId },
  });
  return { accepted: accepted.accepted, operationId: accepted.operationId, liked: accepted.liked };
}
