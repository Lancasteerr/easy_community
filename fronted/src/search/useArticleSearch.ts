import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import type { ArticleCardData } from '../api/types';
import { capturePage } from '../auth/pageLifetime';
import { readSnapshot, saveSnapshot } from '../navigation/snapshots';
import { searchArticles } from './api';
import type { SearchSort } from './routes';

interface SearchState {
  items: ArticleCardData[];
  nextCursor: string | null;
  status: 'loading' | 'ready' | 'error' | 'resetRequired';
  error: unknown;
  paused: boolean;
  retryAt: number;
}
interface SearchSnapshot { state: SearchState; hasLoaded: boolean; emptyPages: number }
const initial = (): SearchState => ({ items: [], nextCursor: null, status: 'loading', error: null, paused: false, retryAt: 0 });

/** 只在有效关键词的结果会话中使用；历史项和条件由调用方共同构成快照键。 */
export function useArticleSearch(q: string, sort: SearchSort, snapshotKey: string) {
  const [state, setState] = useState<SearchState>(initial);
  const current = useRef(state);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const hasLoaded = useRef(false);
  const emptyPages = useRef(0);
  const publish = useCallback((next: SearchState) => { current.current = next; setState(next); }, []);

  const loadMore = useCallback(async (manual = false) => {
    const previous = current.current;
    if (pending.current || previous.status === 'resetRequired' || previous.retryAt > Date.now()
      || (hasLoaded.current && previous.nextCursor === null)
      || (!manual && (previous.status === 'error' || previous.paused))) return;

    // 同步锁阻止同一帧的多个触底事件；代次同时防御忽略取消信号的读取方。
    const controller = new AbortController();
    const active = capturePage();
    const version = generation.current;
    pending.current = controller;
    publish({ ...previous, status: 'loading', error: null, paused: false, retryAt: 0 });
    try {
      const page = await searchArticles(q, sort, previous.nextCursor, controller.signal);
      if (version !== generation.current || !active() || controller.signal.aborted) return;
      const seen = new Set(previous.items.map((item) => item.articleId));
      const added = page.items.filter((item) => {
        if (seen.has(item.articleId)) return false;
        seen.add(item.articleId); return true;
      });
      emptyPages.current = added.length ? 0 : emptyPages.current + 1;
      hasLoaded.current = true;
      const repeated = page.nextCursor !== null && page.nextCursor === previous.nextCursor;
      publish({ items: [...previous.items, ...added], nextCursor: page.nextCursor,
        status: repeated ? 'resetRequired' : 'ready', error: null,
        paused: page.nextCursor !== null && emptyPages.current >= 3, retryAt: 0 });
    } catch (error) {
      if (version !== generation.current || !active() || controller.signal.aborted) return;
      const reset = error instanceof ApiError && ['ARTICLE_CURSOR_EXPIRED', 'ARTICLE_SEARCH_INVALID'].includes(error.code);
      publish({ ...previous, status: reset ? 'resetRequired' : 'error', error,
        retryAt: error instanceof ApiError ? error.retryAt : 0 });
    } finally {
      if (version === generation.current) pending.current = null;
    }
  }, [q, sort, publish]);

  const restart = useCallback(() => {
    if (current.current.retryAt > Date.now()) return;
    generation.current++;
    pending.current?.abort(); pending.current = null;
    hasLoaded.current = false; emptyPages.current = 0;
    publish(initial());
    void loadMore();
  }, [loadMore, publish]);

  useEffect(() => {
    const saved = readSnapshot<SearchSnapshot>(snapshotKey);
    hasLoaded.current = saved?.hasLoaded ?? false;
    emptyPages.current = saved?.emptyPages ?? 0;
    publish(saved?.state ?? initial());
    if (!saved) void loadMore();
    return () => {
      // 续页取消后恢复为可读状态；错误和失效状态保留，不能自动重复请求。
      if (hasLoaded.current) saveSnapshot<SearchSnapshot>(snapshotKey, {
        state: { ...current.current, status: current.current.status === 'loading' ? 'ready' : current.current.status },
        hasLoaded: true, emptyPages: emptyPages.current,
      });
      generation.current++; pending.current?.abort(); pending.current = null;
    };
  }, [snapshotKey, loadMore, publish]);

  return { ...state, loadMore, restart };
}
