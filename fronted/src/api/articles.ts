import { request } from './client';
import type { ArticleDetailData, ArticleLikeAccepted, CsrfToken, FeedPage } from './types';
import { savePayload, type ArticleEditData, type ArticleForm, type Tag } from '../editor/model';

/** 本人和访客主页共用公开作者筛选，游标不可与本人管理列表混用。 */
export function getAuthorArticles(userId: string, cursor: string | null, signal?: AbortSignal) {
  const query = new URLSearchParams({ authorId: userId, sort: 'latest', size: '20' });
  if (cursor !== null) query.set('cursor', cursor);
  return request<FeedPage>(`/api/articles/search?${query}`, { signal });
}

/** 公开详情遇认证失效也由会话边界重置，不静默降为游客重读。 */
export function getArticleDetail(id: string, signal?: AbortSignal) {
  return request<ArticleDetailData>(`/api/articles/${encodeURIComponent(id)}`, { signal });
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
