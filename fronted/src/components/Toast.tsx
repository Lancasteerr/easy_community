import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

const ToastContext = createContext<(message: string) => void>(() => {});

/** 全站仅展示一条轻量反馈，重复点击会延长提示而不会叠加遮挡内容。 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const notify = useCallback((text: string) => {
    clearTimeout(timer.current);
    setMessage(text);
    timer.current = setTimeout(() => setMessage(''), 3000);
  }, []);
  useEffect(() => () => clearTimeout(timer.current), []);
  return <ToastContext.Provider value={notify}>
    {children}<div className={`toast${message ? ' toast-visible' : ''}`} role="status" aria-live="polite">{message}</div>
  </ToastContext.Provider>;
}

export const useToast = () => useContext(ToastContext);

export function useUnavailable() {
  const notify = useToast();
  return useCallback(() => notify('功能暂未开放'), [notify]);
}
