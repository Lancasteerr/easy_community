import { useEffect, useRef, useState } from 'react';
import { getArticleEdit } from '../api/articles';
import { ApiError } from '../api/client';
import { emptyForm, formKey, stripTitle, type ArticleForm } from './model';

export type ReadState = 'loading' | 'ready' | 'error' | 'missing' | 'forbidden' | 'hidden';
/** 自身创建后的 URL 替换不再次读取，主动切换文章则重新检查主库权限。 */
export function useArticleDocument(routeId: string | undefined, onUnauthorized: () => void) {
  const [form, setForm] = useState<ArticleForm>(emptyForm);
  const [baseline, setBaseline] = useState<ArticleForm>(emptyForm);
  const [state, setState] = useState<ReadState>(routeId ? 'loading' : 'ready');
  const [revision, setRevision] = useState(0);
  const [articleId, setArticleId] = useState(routeId);
  const loadedId = useRef<string | undefined>(undefined);
  const callbacks = useRef({ onUnauthorized }); callbacks.current = { onUnauthorized };
  const bodyKey = useRef(0);
  useEffect(() => {
    if (routeId === loadedId.current && revision === 0) return;
    const controller = new AbortController(); setState('loading');
    const read = routeId ? getArticleEdit(routeId, controller.signal) : Promise.resolve(emptyForm());
    read.then((data) => {
      if (controller.signal.aborted) return;
      loadedId.current = routeId; bodyKey.current++;
      setArticleId(routeId); setForm(data); setBaseline(data); setState('ready');
    }).catch((error) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) { callbacks.current.onUnauthorized(); return; }
      setState(error instanceof ApiError && error.code === 'ARTICLE_HIDDEN' ? 'hidden'
        : error instanceof ApiError && error.status === 404 ? 'missing'
        : error instanceof ApiError && error.status === 403 ? 'forbidden' : 'error');
    });
    return () => controller.abort();
  }, [routeId, revision]);
  const saved = (id: string, submitted: ArticleForm) => {
    const normalized = { ...submitted, title: stripTitle(submitted.title) };
    loadedId.current = id; setArticleId(id); setForm(normalized); setBaseline(normalized);
  };
  return { form, setForm, baseline, setBaseline, articleId, state, bodyKey: bodyKey.current,
    dirty: formKey(form) !== formKey(baseline), saved, retry: () => setRevision((value) => value + 1) };
}
