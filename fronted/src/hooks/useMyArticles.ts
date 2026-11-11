import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getMyArticles } from '../api/client';
import type { ArticleCardData } from '../api/types';

type Status = 'initialLoading' | 'ready' | 'loadingMore' | 'refreshing' | 'initialError' | 'appendError' | 'refreshError' | 'resetRequired';
interface MyArticlesState {
  items: ArticleCardData[];
  nextCursor: string | null;
  status: Status;
  error: unknown;
  paused: boolean;
  retryAt: number;
  revision: number;
}
const initial = (): MyArticlesState => ({ items: [], nextCursor: null, status: 'initialLoading', error: null, paused: false, retryAt: 0, revision: 0 });

/** 页面内的游标会话：刷新保留旧展示，删除排除集合独立于读取代次。 */
export function useMyArticles(userId: string, enabled: boolean, onUnauthorized: () => void) {
  const [state, setState] = useState<MyArticlesState>(initial);
  const current = useRef(state);
  const permitted = useRef(enabled);
  permitted.current = enabled;
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const removed = useRef(new Set<string>());
  const emptyPages = useRef(0);
  const publish = useCallback((next: MyArticlesState) => { current.current = next; setState(next); }, []);

  const read = useCallback(async (kind: 'initial' | 'append' | 'refresh', manual = false) => {
    const previous = current.current;
    if (!permitted.current || previous.retryAt > Date.now()) return;
    if (kind === 'refresh') {
      if (previous.status === 'refreshing') return;
      generation.current++;
      pending.current?.abort();
      pending.current = null;
    } else if (pending.current || (kind === 'append' && (previous.nextCursor === null
      || !['ready', 'appendError'].includes(previous.status) || (!manual && (previous.paused || previous.status === 'appendError'))))) return;
    const controller = new AbortController();
    const version = generation.current;
    pending.current = controller;
    const cursor = kind === 'append' ? previous.nextCursor : null;
    publish({ ...previous, status: kind === 'append' ? 'loadingMore' : kind === 'refresh' ? 'refreshing' : 'initialLoading', error: null, retryAt: 0 });
    try {
      const page = await getMyArticles(cursor, controller.signal);
      if (version !== generation.current || controller.signal.aborted) return;
      // 读取期间允许删除完成；合并必须以最新展示和排除集合为准，不能恢复请求前的旧数组。
      const latest = current.current;
      const base = kind === 'append' ? latest.items : [];
      const seen = new Set(base.map((item) => item.articleId));
      const added = page.items.filter((item) => {
        if (removed.current.has(item.articleId) || seen.has(item.articleId)) return false;
        seen.add(item.articleId); return true;
      });
      emptyPages.current = added.length ? 0 : (kind === 'append' ? emptyPages.current : 0) + 1;
      const repeated = page.nextCursor !== null && page.nextCursor === cursor;
      publish({ items: [...base, ...added], nextCursor: page.nextCursor,
        status: repeated ? 'resetRequired' : 'ready', error: repeated ? new ApiError(400, 'REPEATED_CURSOR', '列表未能继续，请重新加载') : null,
        paused: page.nextCursor !== null && emptyPages.current >= 3, retryAt: 0,
        revision: latest.revision + (kind === 'refresh' ? 1 : 0) });
    } catch (error) {
      if (version !== generation.current || controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) { onUnauthorized(); return; }
      const reset = error instanceof ApiError && ['ARTICLE_CURSOR_EXPIRED', 'ARTICLE_SEARCH_INVALID'].includes(error.code);
      publish({ ...current.current, status: reset ? 'resetRequired' : kind === 'append' ? 'appendError' : kind === 'refresh' ? 'refreshError' : 'initialError',
        error, retryAt: error instanceof ApiError ? error.retryAt : 0 });
    } finally {
      if (version === generation.current) pending.current = null;
    }
  }, [onUnauthorized, publish]);

  useEffect(() => {
    generation.current++;
    removed.current.clear(); emptyPages.current = 0;
    publish(initial());
    return () => { generation.current++; pending.current?.abort(); pending.current = null; };
  }, [userId, publish]);
  useEffect(() => {
    if (enabled && state.status === 'initialLoading' && !pending.current) void read('initial');
  }, [enabled, state.status, read]);

  const remove = useCallback((id: string) => {
    removed.current.add(id);
    publish({ ...current.current, items: current.current.items.filter((item) => item.articleId !== id) });
  }, [publish]);
  return { ...state, remove, refresh: () => read('refresh'), retry: () => read(current.current.status === 'initialError' ? 'initial' : 'append', true),
    loadMore: useCallback((manual = false) => read('append', manual), [read]) };
}
