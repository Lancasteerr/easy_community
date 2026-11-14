import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError, getSession } from '../api/client';
import type { SessionIdentity } from '../api/types';
import { setSnapshotOwner } from '../navigation/snapshots';

export type SessionEndReason = 'verification' | 'action' | 'logout';

export interface AuthState {
  status: 'loading' | 'guest' | 'authenticated' | 'error';
  identity: SessionIdentity | null;
  error: unknown;
  /** 后台复核不改变已确认身份，受保护页面可据此暂停新操作。 */
  refreshing: boolean;
  /** 被动复核回首页，主动操作失效才进入登录；登出不触发编辑恢复。 */
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
  const [state, setState] = useState<AuthState>({ status: 'loading', identity: null, error: null, refreshing: false, endReason: null });
  const current = useRef(state);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const publish = useCallback((next: AuthState) => {
    if (next.status === 'guest') setSnapshotOwner(null);
    else if (next.identity) setSnapshotOwner(next.identity.userId);
    current.current = next; setState(next); return next;
  }, []);
  const expireSession = useCallback((reason: SessionEndReason = 'action') => {
    // 业务接口确认401后，同时作废在途会话读取，防止旧响应恢复已经过期的身份。
    generation.current++;
    pending.current?.abort();
    pending.current = null;
    publish({ status: 'guest', identity: null, error: null, refreshing: false, endReason: reason });
  }, [publish]);
  const acceptSession = useCallback((identity: SessionIdentity) => {
    // 登录成功立即作废旧会话复核，只保存协议允许的最小身份。
    generation.current++;
    pending.current?.abort(); pending.current = null;
    publish({ status: 'authenticated', identity: { userId: identity.userId, role: identity.role }, error: null, refreshing: false, endReason: null });
  }, [publish]);
  const refresh = useCallback(async () => {
    if (current.current.error instanceof ApiError && current.current.error.retryAt > Date.now()) return current.current;
    const version = ++generation.current;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    const fallback = current.current.status === 'authenticated' ? current.current.identity : null;
    publish({ status: fallback ? 'authenticated' : 'loading', identity: fallback, error: null, refreshing: true, endReason: null });
    try {
      const identity = await getSession(controller.signal);
      if (version === generation.current && !controller.signal.aborted) return publish({ status: 'authenticated', identity: { userId: identity.userId, role: identity.role }, error: null, refreshing: false, endReason: null });
    } catch (error) {
      if (version !== generation.current || controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) return publish({ status: 'guest', identity: null, error: null, refreshing: false, endReason: 'verification' });
      return publish({ status: fallback ? 'authenticated' : 'error', identity: fallback, error, refreshing: false, endReason: null });
    }
  }, [publish]);

  useEffect(() => {
    void refresh();
    const onFocus = () => { void refresh(); };
    window.addEventListener('focus', onFocus);
    return () => { generation.current++; pending.current?.abort(); window.removeEventListener('focus', onFocus); };
  }, [refresh]);

  return <AuthContext.Provider value={{ ...state, refresh, expireSession, acceptSession }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('登录态组件必须位于 AuthProvider 内');
  return value;
}
