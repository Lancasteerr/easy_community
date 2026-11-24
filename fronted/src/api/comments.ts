import { request } from './client';
import type { ArticleLikeAccepted, CsrfToken } from './types';

export type CommentSort = 'hot' | 'latest';

/** 回复沿用楼层和被回复对象的字符串标识。 */
export interface CommentItem {
  id: string;
  rootId: string | null;
  replyToId: string | null;
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
  const query = cursor === null ? '' : `?${new URLSearchParams({ cursor })}`;
  return request<CommentPage>(path + query, { signal });
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
