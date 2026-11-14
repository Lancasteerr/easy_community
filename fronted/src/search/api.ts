import { request } from '../api/client';
import type { FeedPage } from '../api/types';
import type { SearchSort } from './routes';

/** 调用前关键词已通过页面校验；游标属于当前关键词、排序和固定页大小。 */
export function searchArticles(q: string, sort: SearchSort, cursor: string | null, signal?: AbortSignal) {
  const params = new URLSearchParams({ q, sort, size: '20' });
  if (cursor !== null) params.set('cursor', cursor);
  return request<FeedPage>(`/api/articles/search?${params}`, { signal });
}
