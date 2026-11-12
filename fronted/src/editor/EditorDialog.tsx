import { useLayoutEffect, useRef, type ReactNode } from 'react';

/** 原生模态层隔离背景，显式控制首尾焦点、Esc 与关闭后的焦点归还。 */
export function EditorDialog({ title, busy = false, onClose, children }: {
  title: string; busy?: boolean; onClose: () => void; children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const callback = useRef(onClose); callback.current = onClose;
  useLayoutEffect(() => {
    const element = dialog.current!;
    const trigger = document.activeElement as HTMLElement | null;
    element.showModal();
    (element.querySelector<HTMLElement>('[data-autofocus]:not(:disabled), button:not(:disabled)') ?? element).focus({ preventScroll: true });
    return () => { element.close(); if (trigger?.isConnected) trigger.focus({ preventScroll: true }); };
  }, []);
  useLayoutEffect(() => {
    const element = dialog.current!;
    if (busy) element.focus({ preventScroll: true });
    else (element.querySelector<HTMLElement>('[data-autofocus]:not(:disabled), button:not(:disabled)') ?? element).focus({ preventScroll: true });
  }, [busy]);
  return <dialog ref={dialog} className="editor-dialog" tabIndex={-1} aria-label={title}
    onCancel={(event) => { event.preventDefault(); if (!busy) callback.current(); }} onKeyDown={(event) => {
      if (event.key !== 'Tab') return;
      const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), [tabindex="0"]'));
      const first = controls[0], last = controls[controls.length - 1];
      if (!first) { event.preventDefault(); dialog.current?.focus(); }
      else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.current)) { event.preventDefault(); first.focus(); }
    }}><h2>{title}</h2>{children}</dialog>;
}
