import { useCallback, useEffect, useRef, useState } from 'react';
import { getAuthorArticles } from '../api/articles';
import { ApiError } from '../api/client';
import type { ArticleCardData } from '../api/types';
import { capturePage } from '../auth/pageLifetime';
import { readSnapshot, saveSnapshot } from '../navigation/snapshots';

type Status = 'initialLoading' | 'loadingMore' | 'refreshing' | 'ready' | 'initialError' | 'appendError' | 'refreshError' | 'resetRequired';
interface ArticlesState {
  items: ArticleCardData[];
  nextCursor: string | null;
  status: Status;
  paused: boolean;
  retryAt: number;
  revision: number;
}
interface Snapshot { state: ArticlesState; emptyPages: number }
const initial = (): ArticlesState => ({ items: [], nextCursor: null, status: 'initialLoading', paused: false, retryAt: 0, revision: 0 });

/** 公开作者列表只保存当前文档的历史会话；调用前提是目标资料已读取成功。 */
export function useProfileArticles(userId: string, enabled: boolean, snapshotKey: string) {
  const active = capturePage();
  const [state, setState] = useState<ArticlesState>(initial);
  const current = useRef(state);
  const permitted = useRef(enabled); permitted.current = enabled;
  const generation = useRef(0);
  const pending = useRef<AbortController | null>(null);
  const emptyPages = useRef(0);
  const publish = useCallback((next: ArticlesState) => { current.current = next; setState(next); }, []);
  const read = useCallback(async (kind: 'initial' | 'append' | 'reset', manual = false) => {
    const previous = current.current;
    if (!permitted.current || pending.current || previous.retryAt > Date.now()) return;
    if (kind === 'append' && (previous.nextCursor === null || !['ready', 'appendError'].includes(previous.status)
      || (!manual && (previous.paused || previous.status === 'appendError')))) return;
    const controller = new AbortController();
    const version = generation.current;
    pending.current = controller;
    const cursor = kind === 'append' ? previous.nextCursor : null;
    publish({ ...previous, status: kind === 'append' ? 'loadingMore' : kind === 'reset' ? 'refreshing' : 'initialLoading', retryAt: 0 });
    try {
      const page = await getAuthorArticles(userId, cursor, controller.signal);
      if (version !== generation.current || !active() || controller.signal.aborted) return;
      const base = kind === 'append' ? previous.items : [];
      const seen = new Set(base.map((item) => item.articleId));
      const added = page.items.filter((item) => {
        if (seen.has(item.articleId)) return false;
        seen.add(item.articleId); return true;
      });
      emptyPages.current = added.length ? 0 : (kind === 'append' ? emptyPages.current : 0) + 1;
      const repeated = page.nextCursor !== null && page.nextCursor === cursor;
      publish({ items: [...base, ...added], nextCursor: page.nextCursor, status: repeated ? 'resetRequired' : 'ready',
        paused: page.nextCursor !== null && emptyPages.current >= 3, retryAt: 0,
        revision: previous.revision + (kind === 'reset' ? 1 : 0) });
    } catch (error) {
      if (version !== generation.current || !active() || controller.signal.aborted) return;
      const reset = error instanceof ApiError && ['ARTICLE_CURSOR_EXPIRED', 'ARTICLE_SEARCH_INVALID'].includes(error.code);
      publish({ ...previous, status: reset ? 'resetRequired' : kind === 'append' ? 'appendError' : kind === 'reset' ? 'refreshError' : 'initialError',
        retryAt: error instanceof ApiError ? error.retryAt : 0 });
    } finally {
      if (version === generation.current) pending.current = null;
    }
  }, [userId, publish]);

  useEffect(() => {
    generation.current++;
    const saved = readSnapshot<Snapshot>(snapshotKey);
    emptyPages.current = saved?.emptyPages ?? 0;
    publish(saved?.state ?? initial());
    return () => {
      const latest = current.current;
      if (!['initialLoading', 'initialError'].includes(latest.status)) {
        // 取消续页后允许继续读取；取消重开查询后不能重新使用已经失效的旧游标。
        const status = latest.status === 'loadingMore' ? 'ready' : latest.status === 'refreshing' ? 'resetRequired' : latest.status;
        saveSnapshot<Snapshot>(snapshotKey, { state: { ...latest, status }, emptyPages: emptyPages.current });
      }
      generation.current++; pending.current?.abort(); pending.current = null;
    };
  }, [userId, snapshotKey, publish]);
  useEffect(() => {
    if (enabled && current.current.status === 'initialLoading' && !pending.current) void read('initial');
  }, [enabled, state.status, read]);

  return { ...state,
    loadMore: useCallback((manual = false) => read('append', manual), [read]),
    restart: useCallback(() => read('reset', true), [read]),
    retry: useCallback(() => read(current.current.status === 'initialError' ? 'initial' : 'append', true), [read]),
  };
}
