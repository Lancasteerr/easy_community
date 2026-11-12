import { useEffect, useRef, useState } from 'react';
import { saveArticle } from '../api/articles';
import { ApiError, getCsrf } from '../api/client';
import { validateForm, type ArticleForm } from './model';

export interface SaveState { status: 'pending' | 'success' | 'failure'; editing: boolean; message: string; retryAt: number }
/** 每次用户操作只执行一次 POST；失败统一反馈，不查询结果，也不重放写入。 */
export function useArticleSave(onSaved: (id: string, form: ArticleForm) => void, onUnauthorized: () => void) {
  const [dialog, setDialog] = useState<SaveState | null>(null);
  const [errors, setErrors] = useState<{ title?: string; content?: string }>({});
  const pending = useRef<AbortController | null>(null);
  const retryAfter = useRef(0);
  const callbacks = useRef({ onSaved, onUnauthorized }); callbacks.current = { onSaved, onUnauthorized };
  useEffect(() => () => { pending.current?.abort(); }, []);
  async function submit(form: ArticleForm, articleId: string | undefined, blocked: boolean) {
    if (pending.current || blocked || retryAfter.current > Date.now()) return;
    const invalid = validateForm(form, articleId); setErrors(invalid);
    if (invalid.title || invalid.content) return;
    const controller = new AbortController(); pending.current = controller;
    const editing = !!articleId;
    setDialog({ status: 'pending', editing, message: '正在提交文章，请稍候。', retryAt: 0 });
    try {
      const csrf = await getCsrf(controller.signal);
      const result = await saveArticle(form, articleId, csrf, controller.signal);
      if (controller.signal.aborted) return;
      if (!/^[1-9]\d{0,18}$/.test(result.articleId)) throw new Error('无效保存响应');
      callbacks.current.onSaved(result.articleId, form);
      setDialog({ status: 'success', editing, message: editing ? '修改已保存。' : '文章已发布。', retryAt: 0 });
    } catch (error) {
      if (controller.signal.aborted) return;
      retryAfter.current = error instanceof ApiError ? error.retryAt : 0;
      const message = error instanceof ApiError && error.code === 'MEDIA_STATE_CONFLICT' ? '图片尚未就绪或不可绑定，请稍后重试。'
        : error instanceof ApiError && error.status === 401 ? '登录已失效，请重新登录后提交。'
        : error instanceof ApiError && error.status === 403 ? '操作凭证失效或没有编辑权限，请确认后重试。'
        : error instanceof ApiError && error.code === 'ARTICLE_HIDDEN' ? '文章已删除，无法保存修改。'
        : error instanceof ApiError && error.status === 404 ? '文章或引用的图片不存在，请检查后重试。'
        : error instanceof ApiError && error.status === 429 ? '请求过于频繁，请稍后重试。'
        : '未收到保存成功确认，内容已保留，请重试。';
      setDialog({ status: 'failure', editing, message, retryAt: retryAfter.current });
      if (error instanceof ApiError && error.status === 401) callbacks.current.onUnauthorized();
    } finally { if (pending.current === controller) pending.current = null; }
  }
  return { dialog, errors, setErrors, submit, busy: dialog?.status === 'pending', close: () => { if (!pending.current) setDialog(null); } };
}
