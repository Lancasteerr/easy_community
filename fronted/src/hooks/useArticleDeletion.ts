import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, checkDeletion, deleteArticle, errorMessage, getCsrf } from '../api/client';
import type { ArticleCardData, CsrfToken } from '../api/types';
import type { DeleteDialogState } from '../components/manage/DeleteArticleDialog';

const initial = (): DeleteDialogState => ({ article: null, busy: null, unknown: false, canCheck: false, message: '', retryAt: 0 });

/** 写入与结果核对独立于列表请求；关闭弹窗仍保留本页尚未核实的文章ID。 */
export function useArticleDeletion(enabled: boolean, onUnauthorized: () => void, onRemoved: (id: string, message: string) => void) {
  const [state, setState] = useState<DeleteDialogState>(initial);
  const current = useRef(state);
  const options = useRef({ enabled, onUnauthorized, onRemoved });
  options.current = { enabled, onUnauthorized, onRemoved };
  const [uncertainIds, setUncertainIds] = useState<ReadonlySet<string>>(new Set());
  const uncertain = useRef(new Set<string>());
  const csrf = useRef<CsrfToken | null>(null);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const retryAt = useRef(0);
  const publish = useCallback((next: DeleteDialogState) => { current.current = next; setState(next); }, []);
  const mark = useCallback((id: string, unknown: boolean) => {
    if (unknown) uncertain.current.add(id); else uncertain.current.delete(id);
    setUncertainIds(new Set(uncertain.current));
  }, []);
  useEffect(() => () => { generation.current++; pending.current?.abort(); pending.current = null; }, []);

  const open = useCallback((article: ArticleCardData) => {
    if (!options.current.enabled || pending.current) return;
    const unknown = uncertain.current.has(article.articleId);
    publish({ ...initial(), article, unknown, canCheck: unknown, retryAt: retryAt.current,
      message: unknown ? '此前删除结果尚未确认，请先核对结果。' : '' });
  }, [publish]);
  const close = useCallback(() => { if (!pending.current) publish(initial()); }, [publish]);

  const operate = useCallback(async (kind: 'delete' | 'check') => {
    const before = current.current;
    if (!before.article || pending.current || !options.current.enabled || retryAt.current > Date.now()
      || (kind === 'delete' && before.unknown) || (kind === 'check' && !before.canCheck)) return;
    const article = before.article;
    const controller = new AbortController();
    const version = generation.current;
    pending.current = controller;
    publish({ ...before, busy: kind, message: '', retryAt: 0 });
    let sent = false;
    const active = () => !controller.signal.aborted && version === generation.current;
    const finish = (message: string) => {
      mark(article.articleId, false);
      publish(initial());
      options.current.onRemoved(article.articleId, message);
    };
    try {
      if (kind === 'check') {
        const result = await checkDeletion(article.articleId, controller.signal);
        if (!active()) return;
        if (result !== 'visible') { finish(result === 'hidden' ? '内容已删除' : '内容已不存在'); return; }
        mark(article.articleId, false);
        publish({ ...current.current, busy: null, unknown: false, canCheck: true,
          message: '内容当前仍可读取，可稍后核对或再次确认删除。' });
      } else {
        // 取得令牌是发送DELETE的前提；前置读取失败不能报告为删除结果未知。
        csrf.current ??= await getCsrf(controller.signal);
        if (!active()) return;
        if (!options.current.enabled) {
          publish({ ...current.current, busy: null, message: '请确认登录状态后重试。' }); return;
        }
        sent = true;
        await deleteArticle(article.articleId, csrf.current, controller.signal);
        if (active()) finish('删除成功');
      }
    } catch (error) {
      if (!active()) return;
      if (error instanceof ApiError && error.status === 401) { options.current.onUnauthorized(); return; }
      retryAt.current = error instanceof ApiError ? error.retryAt : 0;
      if (kind === 'check') {
        publish({ ...current.current, busy: null, message: `暂时无法核对：${errorMessage(error)}`, retryAt: retryAt.current });
      } else if (sent && error instanceof ApiError && error.status === 404 && error.code === 'ARTICLE_NOT_FOUND') {
        finish('内容已不存在');
      } else if (sent && error instanceof ApiError && error.status === 403 && error.code === 'ACCESS_OR_CSRF_DENIED') {
        csrf.current = null;
        try {
          csrf.current = await getCsrf(controller.signal);
          if (active()) publish({ ...current.current, busy: null, message: '操作凭证已刷新，请再次确认删除。' });
        } catch (refreshError) {
          if (!active()) return;
          if (refreshError instanceof ApiError && refreshError.status === 401) { options.current.onUnauthorized(); return; }
          retryAt.current = refreshError instanceof ApiError ? refreshError.retryAt : 0;
          publish({ ...current.current, busy: null, message: '操作凭证暂时无法刷新，请稍后重试。', retryAt: retryAt.current });
        }
      } else {
        const unknown = sent && (!(error instanceof ApiError) || error.status === 0
          || error.code === 'ARTICLE_WRITE_RESULT_UNKNOWN' || (error.status >= 500 && error.code !== 'DEPENDENCY_UNAVAILABLE'));
        if (unknown) mark(article.articleId, true);
        const message = unknown ? '删除结果尚未确认，请核对结果；离开页面不会撤销已发送的操作。'
          : !sent ? `暂时无法获取操作凭证：${errorMessage(error)}`
          : error instanceof ApiError && error.status === 403 ? '没有删除此文章的权限。'
          : error instanceof ApiError && error.status === 429 ? errorMessage(error) : '删除失败，请重试';
        publish({ ...current.current, busy: null, unknown, canCheck: unknown || current.current.canCheck, message, retryAt: retryAt.current });
      }
    } finally {
      if (active()) pending.current = null;
    }
  }, [mark, publish]);
  return { state, uncertainIds, open, close, confirm: () => operate('delete'), check: () => operate('check') };
}
