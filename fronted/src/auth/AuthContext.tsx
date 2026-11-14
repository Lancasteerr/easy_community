import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError, getSession } from '../api/client';
import type { SessionIdentity } from '../api/types';
import { endPage, isPageActive, pageLifetime, renewPage } from './pageLifetime';
import { leaveAfterSessionChange } from './sessionNavigation';
import { clearRecovery } from '../editor/recovery';
import { setSnapshotOwner } from '../navigation/snapshots';
import { documentNavigation } from '../navigation/documentNavigation';

export type SessionEndReason = 'verification' | 'action' | 'logout';

export interface AuthState {
  status: 'loading' | 'guest' | 'authenticated' | 'error';
  identity: SessionIdentity | null;
  error: unknown;
  /** 身份变化时卸载旧页面，等待整页导航完成。 */
  resetting: boolean;
  /** 后台复核不改变已确认身份，受保护页面可据此暂停新操作。 */
  refreshing: boolean;
  /** 保留失效原因供状态诊断，跳转统一按当前页面分流。 */
  endReason: SessionEndReason | null;
}

interface AuthContextValue extends AuthState {
  refresh: () => Promise<AuthState | undefined>;
  expireSession: (reason?: SessionEndReason) => void;
  acceptSession: (identity: SessionIdentity) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** 会话接口是登录态来源；网络故障不能等价于游客或抹掉刚完成的登录。 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', identity: null, error: null, refreshing: false, endReason: null, resetting: false });
  const current = useRef(state);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const publish = useCallback((next: AuthState) => {
    if (next.status === 'guest') setSnapshotOwner(null);
    else if (next.identity) setSnapshotOwner(next.identity.userId);
    current.current = next; setState(next); return next;
  }, []);
  const expireSession = useCallback((reason: SessionEndReason = 'action') => {
    if (!isPageActive()) return;
    // 业务接口确认401后，同时作废在途会话读取，防止旧响应恢复已经过期的身份。
    generation.current++;
    pending.current?.abort();
    pending.current = null;
    const resetting = leaveAfterSessionChange();
    publish({ status: 'guest', identity: null, error: null, refreshing: false, endReason: reason, resetting });
  }, [publish]);
  const acceptSession = useCallback((identity: SessionIdentity) => {
    if (!isPageActive()) return;
    renewPage();
    // 登录成功立即作废旧会话复核，只保存协议允许的最小身份。
    generation.current++;
    pending.current?.abort(); pending.current = null;
    publish({ status: 'authenticated', identity: { userId: identity.userId, role: identity.role }, error: null, refreshing: false, endReason: null, resetting: false });
  }, [publish]);
  const refresh = useCallback(async () => {
    if (!isPageActive()) return;
    if (current.current.error instanceof ApiError && current.current.error.retryAt > Date.now()) return current.current;
    const version = ++generation.current;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    const fallback = current.current.status === 'authenticated' ? current.current.identity : null;
    publish({ status: fallback ? 'authenticated' : 'loading', identity: fallback, error: null, refreshing: true, endReason: null, resetting: false });
    try {
      const identity = await getSession(controller.signal);
      if (version !== generation.current || controller.signal.aborted || !isPageActive()) return;
      const changed = fallback && (fallback.userId !== identity.userId || fallback.role !== identity.role);
      const resetting = changed ? leaveAfterSessionChange() : false;
      return publish({ status: 'authenticated', identity: { userId: identity.userId, role: identity.role }, error: null, refreshing: false, endReason: null, resetting });
    } catch (error) {
      if (version !== generation.current || controller.signal.aborted || !isPageActive()) return;
      if (error instanceof ApiError && error.status === 401) {
        if (fallback) { expireSession('verification'); return; }
        return publish({ status: 'guest', identity: null, error: null, refreshing: false, endReason: 'verification', resetting: false });
      }
      return publish({ status: fallback ? 'authenticated' : 'error', identity: fallback, error, refreshing: false, endReason: null, resetting: false });
    }
  }, [publish, expireSession]);

  useEffect(() => {
    clearRecovery();
    pageLifetime.onUnauthorized = expireSession;
    void refresh();
    const onFocus = () => { void refresh(); };
    // 浏览器可能从往返缓存复活整个旧文档；此时必须重新读取，不能恢复原账号画面。
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      endPage(); clearRecovery(); documentNavigation.reload();
    };
    window.addEventListener('focus', onFocus);
    window.addEventListener('pageshow', onPageShow);
    return () => {
      if (pageLifetime.onUnauthorized === expireSession) pageLifetime.onUnauthorized = null;
      generation.current++; pending.current?.abort();
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('pageshow', onPageShow);
    };
  }, [refresh, expireSession]);

  return <AuthContext.Provider value={{ ...state, refresh, expireSession, acceptSession }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('登录态组件必须位于 AuthProvider 内');
  return value;
}
