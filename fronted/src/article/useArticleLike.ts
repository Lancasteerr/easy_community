import { useEffect, useRef, useState } from 'react';
import { setArticleLike } from '../api/articles';
import { ApiError, errorMessage, getCsrf } from '../api/client';
import type { ArticleDetailData, CsrfToken } from '../api/types';

interface LikeOptions {
  enabled: boolean;
  userId?: string;
  onUnauthorized: () => void;
  notify: (message: string) => void;
}

/** 成功受理后才更新展示；超时保留原状态，校正留给用户手动刷新页面。 */
export function useArticleLike(article: ArticleDetailData, options: LikeOptions) {
  const [state, setState] = useState({ liked: article.likedByMe, count: article.likeCount, busy: false, retryAt: 0 });
  const current = useRef(state);
  const pending = useRef<AbortController | null>(null);
  const csrf = useRef<CsrfToken | null>(null);
  const generation = useRef(0);
  const callbacks = useRef(options); callbacks.current = options;
  const publish = (next: typeof state) => { current.current = next; setState(next); };

  useEffect(() => {
    csrf.current = null;
    publish({ liked: article.likedByMe, count: article.likeCount, busy: false, retryAt: 0 });
    return () => { generation.current++; pending.current?.abort(); pending.current = null; };
    // 同篇文章不因正文展示重渲染而覆盖本地已受理状态。
  }, [article.articleId, options.userId]);

  async function toggle() {
    const previous = current.current;
    if (!callbacks.current.enabled || pending.current || previous.retryAt > Date.now()) return;
    const controller = new AbortController();
    const version = generation.current;
    pending.current = controller;
    publish({ ...previous, busy: true, retryAt: 0 });
    let writing = false;
    const fresh = () => version === generation.current && !controller.signal.aborted;
    try {
      csrf.current ??= await getCsrf(controller.signal);
      if (!fresh() || !callbacks.current.enabled) return;
      const operationId = crypto.randomUUID();
      writing = true;
      const accepted = await setArticleLike(article.articleId, !previous.liked, operationId, csrf.current, controller.signal);
      if (!fresh()) return;
      if (accepted.accepted !== true || accepted.operationId !== operationId || typeof accepted.liked !== 'boolean') {
        // 无法识别的受理响应也不能推断写入失败，更不能再次提交。
        return;
      }
      publish({ liked: accepted.liked, count: Math.max(0, previous.count + (accepted.liked === previous.liked ? 0 : accepted.liked ? 1 : -1)),
        busy: true, retryAt: 0 });
    } catch (error) {
      if (!fresh()) return;
      // 只对已经尝试发送的写入静默处理未知结果；获取凭证失败仍需正常反馈。
      if (writing && error instanceof ApiError && (error.status === 0 || error.code === 'LIKE_WRITE_RESULT_UNKNOWN')) return;
      if (error instanceof ApiError && error.status === 401) {
        callbacks.current.onUnauthorized();
      } else if (error instanceof ApiError && error.status === 403) {
        csrf.current = null;
        try {
          csrf.current = await getCsrf(controller.signal);
          if (fresh()) callbacks.current.notify('操作凭证已刷新，请再次点击');
        } catch (refreshError) {
          if (fresh()) {
            if (refreshError instanceof ApiError && refreshError.status === 401) callbacks.current.onUnauthorized();
            else {
              if (refreshError instanceof ApiError) publish({ ...current.current, retryAt: refreshError.retryAt });
              callbacks.current.notify(errorMessage(refreshError));
            }
          }
        }
      } else {
        if (error instanceof ApiError) publish({ ...current.current, retryAt: error.retryAt });
        callbacks.current.notify(error instanceof ApiError && error.status === 404 ? '文章不存在或已删除' : errorMessage(error));
      }
    } finally {
      if (fresh()) { pending.current = null; publish({ ...current.current, busy: false }); }
    }
  }
  return { ...state, toggle };
}
