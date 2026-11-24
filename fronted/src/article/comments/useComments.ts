import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { getComments, type CommentPage, type CommentSort } from '../../api/comments';

/** 排序切换废弃旧请求；下一页失败时保留已展示评论。 */
export function useComments(articleId: string, sort: CommentSort, onCount: (count: number) => void) {
  const [page, setPage] = useState<CommentPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<{ cursor: string | null } | null>(null);
  const pending = useRef<AbortController | null>(null);
  const load = useCallback(async (cursor: string | null) => {
    if (pending.current) return;
    const controller = new AbortController();
    pending.current = controller;
    setLoading(true);
    setFailure(null);
    try {
      const next = await getComments(articleId, sort, cursor, controller.signal);
      if (controller.signal.aborted) return;
      setPage((previous) => {
        if (cursor === null || previous === null) return next;
        const existing = new Set(previous.items.map((item) => item.id));
        return { ...next, items: [...previous.items, ...next.items.filter((item) => !existing.has(item.id))] };
      });
      if (typeof next.commentCount === 'number') onCount(next.commentCount);
    } catch (error) {
      if (!controller.signal.aborted) {
        const expired = error instanceof ApiError && error.code === 'COMMENT_CURSOR_EXPIRED';
        setFailure({ cursor: expired ? null : cursor });
      }
    } finally {
      if (pending.current === controller) {
        pending.current = null;
        setLoading(false);
      }
    }
  }, [articleId, sort, onCount]);

  useEffect(() => {
    setPage(null);
    void load(null);
    return () => { pending.current?.abort(); pending.current = null; };
  }, [load]);

  return { page, loading, failure, load };
}
