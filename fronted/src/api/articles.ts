import { request } from './client';
import type { CsrfToken } from './types';
import { savePayload, type ArticleEditData, type ArticleForm, type Tag } from '../editor/model';

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
