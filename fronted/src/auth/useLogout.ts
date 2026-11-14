import { useEffect, useRef, useState } from 'react';
import { capturePage } from './pageLifetime';
import { isAuthenticationFailure, ApiError, errorMessage, getCsrf, postLogout } from '../api/client';
import type { CsrfToken } from '../api/types';
import { useToast } from '../components/Toast';
import { clearRecovery } from '../editor/recovery';
import { useCooldown } from '../hooks/useCooldown';
import { useAuth } from './AuthContext';

export interface LogoutOptions {
  beforeLogout?: () => Promise<boolean>;
  onLogoutSuccess?: () => void;
}

/** 确认、请求和导航按顺序执行；失败不会放行编辑器导航或丢弃输入。 */
export function useLogout(options: LogoutOptions) {
  const pageActive = capturePage();
  const auth = useAuth();
  const notify = useToast();
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [retryAt, setRetryAt] = useState(0);
  const seconds = useCooldown(retryAt);
  const locked = useRef(false);
  const pending = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const csrf = useRef<CsrfToken | null>(null);
  const callbacks = useRef(options); callbacks.current = options;
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; pending.current?.abort(); };
  }, []);

  async function logout() {
    if (!pageActive() || locked.current || retryAt > Date.now()) return;
    locked.current = true; setConfirming(true);
    try {
      if (callbacks.current.beforeLogout && !await callbacks.current.beforeLogout()) return;
      if (!pageActive() || !mounted.current) return;
      setConfirming(false); setBusy(true);
      const controller = new AbortController(); pending.current = controller;
      const finish = () => {
        // 只有服务端确认登出后，才清理草稿并放行编辑器的身份边界导航。
        callbacks.current.onLogoutSuccess?.();
        clearRecovery(); auth.expireSession('logout');
      };
      try {
        csrf.current ??= await getCsrf(controller.signal);
        if (!pageActive() || controller.signal.aborted) return;
        await postLogout(csrf.current, controller.signal);
        if (pageActive() && !controller.signal.aborted) finish();
      } catch (error) {
        if (!pageActive() || controller.signal.aborted) return;
        if (isAuthenticationFailure(error)) { finish(); return; }
        if (error instanceof ApiError && error.status === 403) {
          csrf.current = null;
          try {
            csrf.current = await getCsrf(controller.signal);
            if (pageActive() && !controller.signal.aborted) notify('操作凭证已刷新，请再次点击退出登录');
          } catch (refreshError) {
            if (!pageActive() || controller.signal.aborted) return;
            if (isAuthenticationFailure(refreshError)) { finish(); return; }
            if (refreshError instanceof ApiError) setRetryAt(refreshError.retryAt);
            notify(errorMessage(refreshError));
          }
        } else {
          if (error instanceof ApiError) setRetryAt(error.retryAt);
          notify(errorMessage(error));
        }
      }
    } finally {
      locked.current = false; pending.current = null;
      if (pageActive() && mounted.current) { setBusy(false); setConfirming(false); }
    }
  }
  return { logout, busy, confirming, seconds };
}
