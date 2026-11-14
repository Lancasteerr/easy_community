import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { isPageActive } from '../auth/pageLifetime';

interface ToastOptions { placement?: 'bottom' | 'top'; durationMs?: number }
const ToastContext = createContext<(message: string, options?: ToastOptions) => void>(() => {});

/** 全站仅展示一条轻量反馈，重复点击会延长提示而不会叠加遮挡内容。 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState({ message: '', placement: 'bottom' });
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const notify = useCallback((text: string, options?: ToastOptions) => {
    if (!isPageActive()) return;
    clearTimeout(timer.current);
    // 新调用独立选择位置和时长，旧入口仍使用原有底部三秒反馈。
    setToast({ message: text, placement: options?.placement ?? 'bottom' });
    timer.current = setTimeout(() => setToast((current) => ({ ...current, message: '' })), options?.durationMs ?? 3000);
  }, []);
  useEffect(() => () => clearTimeout(timer.current), []);
  return <ToastContext.Provider value={notify}>
    {children}<div className={`toast${toast.placement === 'top' ? ' toast-top' : ''}${toast.message ? ' toast-visible' : ''}`}
      role="status" aria-live="polite">{toast.message}</div>
  </ToastContext.Provider>;
}

export const useToast = () => useContext(ToastContext);

export function useUnavailable() {
  const notify = useToast();
  return useCallback(() => notify('功能暂未开放'), [notify]);
}
