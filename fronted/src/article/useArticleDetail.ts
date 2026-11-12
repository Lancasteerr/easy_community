import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { getArticleDetail } from '../api/articles';
import type { ArticleDetailData } from '../api/types';
import { validArticleId } from './routes';

interface DetailState {
  status: 'loading' | 'ready' | 'missing' | 'error';
  article: ArticleDetailData | null;
  error: unknown;
  retryAt: number;
}
const initial = (): DetailState => ({ status: 'loading', article: null, error: null, retryAt: 0 });

/** 详情只随文章切换或手动重试读取，登录态复核及目录操作不会额外产生 PV。 */
export function useArticleDetail(id: string | undefined) {
  const [state, setState] = useState<DetailState>(initial);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const retryAt = useRef(0);
  const read = useCallback(async () => {
    if (pending.current || retryAt.current > Date.now()) return;
    if (!validArticleId(id)) {
      setState({ ...initial(), status: 'missing' });
      return;
    }
    const controller = new AbortController();
    pending.current = controller;
    const version = ++generation.current;
    setState(initial());
    try {
      const article = await getArticleDetail(id, controller.signal);
      if (version !== generation.current || controller.signal.aborted) return;
      setState({ status: 'ready', article, error: null, retryAt: 0 });
    } catch (error) {
      if (version !== generation.current || controller.signal.aborted) return;
      retryAt.current = error instanceof ApiError ? error.retryAt : 0;
      setState({ status: error instanceof ApiError && error.status === 404 ? 'missing' : 'error',
        article: null, error, retryAt: retryAt.current });
    } finally {
      if (version === generation.current) pending.current = null;
    }
  }, [id]);
  useEffect(() => {
    retryAt.current = 0;
    void read();
    return () => { generation.current++; pending.current?.abort(); pending.current = null; };
  }, [read]);
  return { ...state, retry: read };
}
