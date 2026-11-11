import { useEffect, useRef } from 'react';
import type { ArticleCardData } from '../../api/types';

export interface DeleteDialogState {
  article: ArticleCardData | null;
  busy: 'delete' | 'check' | null;
  unknown: boolean;
  canCheck: boolean;
  message: string;
  retryAt: number;
}

/** 原生模态层负责背景隔离与焦点约束；处理中仅阻止弹窗自身关闭，不拦截路由离开。 */
export function DeleteArticleDialog({ state, seconds = 0, blocked = false, onClose, onConfirm, onCheck }: {
  state: DeleteDialogState; seconds?: number; blocked?: boolean; onClose: () => void; onConfirm: () => void; onCheck: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const articleId = state.article?.articleId;
  useEffect(() => {
    const element = dialog.current;
    if (!element || !articleId) return;
    element.showModal();
    cancel.current?.focus({ preventScroll: true });
    return () => { element.close(); };
  }, [articleId]);
  return <dialog className="manage-dialog" ref={dialog} aria-labelledby="delete-heading" aria-describedby="delete-description"
    onCancel={(event) => { event.preventDefault(); if (!state.busy) onClose(); }}>
    <h2 id="delete-heading">{state.unknown ? '删除结果待确认' : '删除这篇内容？'}</h2>
    <p className="manage-dialog-title">「{state.article?.title}」</p>
    <p id="delete-description">删除后将无法恢复，请确认是否继续。</p>
    <p className="manage-dialog-feedback" role="status">{state.message}</p>
    <div className="manage-dialog-actions">
      <button ref={cancel} disabled={!!state.busy} onClick={onClose}>取消</button>
      {state.canCheck && <button disabled={!!state.busy || blocked || seconds > 0} onClick={onCheck}>
        {state.busy === 'check' ? '核对中…' : seconds > 0 ? `${seconds}秒后重试` : '核对结果'}</button>}
      {!state.unknown && <button className="manage-danger" disabled={!!state.busy || blocked || seconds > 0} onClick={onConfirm}>
        {state.busy === 'delete' ? '删除中…' : seconds > 0 ? `${seconds}秒后重试` : '删除'}</button>}
    </div>
  </dialog>;
}
