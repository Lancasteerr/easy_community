import { ApiError, request } from './client';
import type { ArticleDetailData, ArticleLikeAccepted, CsrfToken } from './types';
import { savePayload, type ArticleEditData, type ArticleForm, type Tag } from '../editor/model';

/** 过期 Cookie 清除后，只允许以游客身份重读一次公开详情。 */
export async function getArticleDetail(id: string, signal?: AbortSignal) {
  const read = () => request<ArticleDetailData>(`/api/articles/${encodeURIComponent(id)}`, { signal });
  try { return await read(); }
  catch (error) {
    if (error instanceof ApiError && error.status === 401 && !signal?.aborted) return read();
    throw error;
  }
}

/** 每次显式操作只发送一个目标状态命令，不自动重放结果未知的写入。 */
export function setArticleLike(id: string, liked: boolean, operationId: string, csrf: CsrfToken, signal?: AbortSignal) {
  return request<ArticleLikeAccepted>(`/api/articles/${encodeURIComponent(id)}/like`, {
    method: liked ? 'PUT' : 'DELETE', signal,
    headers: { [csrf.headerName]: csrf.token, 'X-Operation-Id': operationId },
  });
}

/** 编辑只使用作者接口，避免公开缓存陈旧及额外 PV。 */
export function getArticleEdit(id: string, signal?: AbortSignal) {
  return request<ArticleEditData>(`/api/articles/${encodeURIComponent(id)}/edit`, { signal });
}
export function searchTags(q: string, signal?: AbortSignal) {
  return request<Tag[]>(`/api/tags?${new URLSearchParams({ q, limit: '20' })}`, { signal });
}
export function saveArticle(form: ArticleForm, articleId: string | undefined, csrf: CsrfToken, signal?: AbortSignal) {
  return request<{ articleId: string }>('/api/articles', { method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(savePayload(form, articleId)) });
}
