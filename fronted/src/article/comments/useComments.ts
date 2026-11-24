import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import { ApiError } from '../../api/client';
import { getComments, type CommentPage, type CommentSort } from '../../api/comments';

/** 排序切换废弃旧请求；下一页失败时保留已展示评论。 */
export function useComments(articleId: string, sort: CommentSort, onCount: (count: number) => void, countRevision: RefObject<number>) {
  const [page, setPage] = useState<CommentPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<{ cursor: string | null } | null>(null);
  const pending = useRef<AbortController | null>(null);
  const load = useCallback(async (cursor: string | null) => {
    if (pending.current) return;
    const controller = new AbortController();
    const requestedRevision = countRevision.current;
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
      // 已确认的写入结果优先于提交前开启的列表请求。
      if (typeof next.commentCount === 'number' && countRevision.current === requestedRevision) onCount(next.commentCount);
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
  }, [articleId, sort, onCount, countRevision]);

  useEffect(() => {
    setPage(null);
    void load(null);
    return () => { pending.current?.abort(); pending.current = null; };
  }, [load]);

  return { page, loading, failure, load };
}
