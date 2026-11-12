import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getFeed } from '../api/client';
import type { ArticleCardData } from '../api/types';
import { readSnapshot, saveSnapshot } from '../navigation/snapshots';

interface FeedState {
  items: ArticleCardData[];
  nextCursor: string | null;
  status: 'loading' | 'ready' | 'error' | 'expired';
  error: unknown;
  paused: boolean;
  retryAt: number;
}

const initialState = (): FeedState => ({ items: [], nextCursor: null, status: 'loading', error: null, paused: false, retryAt: 0 });

/** 请求锁同步建立；代次令牌隔离重载前后的响应，取消只负责节省网络资源。 */
export function useFeed(snapshotKey?: string) {
  const [state, setState] = useState<FeedState>(initialState);
  const current = useRef(state);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const hasLoaded = useRef(false);
  const emptyPages = useRef(0);
  const publish = useCallback((next: FeedState) => { current.current = next; setState(next); }, []);

  const loadMore = useCallback(async (manual = false) => {
    const previous = current.current;
    if (pending.current || previous.status === 'expired' || previous.retryAt > Date.now()
      || (hasLoaded.current && previous.nextCursor === null)
      || (!manual && (previous.status === 'error' || previous.paused))) return;

    const controller = new AbortController();
    pending.current = controller;
    const version = generation.current;
    publish({ ...previous, status: 'loading', error: null, paused: false, retryAt: 0 });
    try {
      const page = await getFeed(previous.nextCursor, controller.signal);
      if (version !== generation.current || controller.signal.aborted) return;
      const seen = new Set(previous.items.map((item) => item.articleId));
      const added = page.items.filter((item) => {
        if (seen.has(item.articleId)) return false;
        seen.add(item.articleId); return true;
      });
      emptyPages.current = added.length ? 0 : emptyPages.current + 1;
      hasLoaded.current = true;
      publish({ items: [...previous.items, ...added], nextCursor: page.nextCursor, status: 'ready', error: null,
        paused: page.nextCursor !== null && (emptyPages.current >= 3 || page.nextCursor === previous.nextCursor), retryAt: 0 });
    } catch (error) {
      if (version !== generation.current || controller.signal.aborted) return;
      publish({ ...previous, status: error instanceof ApiError && error.code === 'ARTICLE_CURSOR_EXPIRED' ? 'expired' : 'error',
        error, retryAt: error instanceof ApiError ? error.retryAt : 0 });
    } finally {
      if (version === generation.current) pending.current = null;
    }
  }, [publish]);

  const restart = useCallback(() => {
    generation.current++;
    pending.current?.abort();
    pending.current = null;
    hasLoaded.current = false;
    emptyPages.current = 0;
    publish(initialState());
    void loadMore();
  }, [loadMore, publish]);

  useEffect(() => {
    const saved = readSnapshot<{ state: FeedState; hasLoaded: boolean; emptyPages: number }>(snapshotKey);
    if (saved) {
      hasLoaded.current = saved.hasLoaded; emptyPages.current = saved.emptyPages;
      publish(saved.state);
    } else restart();
    return () => {
      // 续页被取消时恢复为可继续的展示态，不能把 loading 锁带回历史页面。
      if (hasLoaded.current) saveSnapshot(snapshotKey, { state: { ...current.current,
        status: current.current.status === 'loading' ? 'ready' : current.current.status },
        hasLoaded: true, emptyPages: emptyPages.current });
      generation.current++; pending.current?.abort(); pending.current = null;
    };
  }, [restart, publish, snapshotKey]);

  return { ...state, loadMore, restart };
}
