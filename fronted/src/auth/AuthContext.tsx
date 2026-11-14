import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError, getProfile } from '../api/client';
import type { UserIdentity, UserProfile } from '../api/types';
import { setSnapshotOwner } from '../navigation/snapshots';

export type SessionEndReason = 'verification' | 'action' | 'logout';

export interface AuthState {
  status: 'loading' | 'guest' | 'authenticated' | 'error';
  user: UserProfile | null;
  error: unknown;
  /** 后台复核不改变已确认身份，受保护页面可据此暂停新操作。 */
  refreshing: boolean;
  /** 被动复核回首页，主动操作失效才进入登录；登出不触发编辑恢复。 */
  endReason: SessionEndReason | null;
}

interface AuthContextValue extends AuthState {
  refresh: (confirmedIdentity?: UserIdentity) => Promise<AuthState | undefined>;
  expireSession: (reason?: SessionEndReason) => void;
  updateProfile: (userId: string, patch: Partial<Pick<UserProfile, 'bio' | 'avatarMediaId' | 'avatarUrl'>>) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** 用户资料是登录态来源；网络故障不能等价于游客或抹掉刚完成的登录。 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null, error: null, refreshing: false, endReason: null });
  const current = useRef(state);
  const pending = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const publish = useCallback((next: AuthState) => {
    if (next.status === 'guest') setSnapshotOwner(null);
    else if (next.user) setSnapshotOwner(next.user.userId);
    current.current = next; setState(next); return next;
  }, []);
  const expireSession = useCallback((reason: SessionEndReason = 'action') => {
    // 业务接口确认401后，同时作废在途资料读取，防止旧响应恢复已经过期的身份。
    generation.current++;
    pending.current?.abort();
    pending.current = null;
    publish({ status: 'guest', user: null, error: null, refreshing: false, endReason: reason });
  }, [publish]);
  const updateProfile = useCallback((userId: string, patch: Partial<Pick<UserProfile, 'bio' | 'avatarMediaId' | 'avatarUrl'>>) => {
    const previous = current.current;
    // 写入响应不能恢复已失效身份，也不能污染切换后的其他账号。
    if (previous.status !== 'authenticated' || previous.user?.userId !== userId) return false;
    generation.current++;
    pending.current?.abort();
    pending.current = null;
    publish({ ...previous, user: { ...previous.user, ...patch }, refreshing: false, error: null });
    return true;
  }, [publish]);
  const refresh = useCallback(async (confirmedIdentity?: UserIdentity) => {
    if (!confirmedIdentity && current.current.error instanceof ApiError && current.current.error.retryAt > Date.now()) return current.current;
    const version = ++generation.current;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    const fallback: UserProfile | null = confirmedIdentity
      ? { ...confirmedIdentity, avatarMediaId: null, avatarUrl: null, bio: '' }
      : current.current.status === 'authenticated' ? current.current.user : null;
    publish({ status: fallback ? 'authenticated' : 'loading', user: fallback, error: null, refreshing: true, endReason: null });
    try {
      const user = await getProfile(controller.signal);
      if (version === generation.current && !controller.signal.aborted) return publish({ status: 'authenticated', user, error: null, refreshing: false, endReason: null });
    } catch (error) {
      if (version !== generation.current || controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) return publish({ status: 'guest', user: null, error: null, refreshing: false, endReason: 'verification' });
      return publish({ status: fallback ? 'authenticated' : 'error', user: fallback, error, refreshing: false, endReason: null });
    }
  }, [publish]);

  useEffect(() => {
    void refresh();
    const onFocus = () => { void refresh(); };
    window.addEventListener('focus', onFocus);
    return () => { generation.current++; pending.current?.abort(); window.removeEventListener('focus', onFocus); };
  }, [refresh]);

  return <AuthContext.Provider value={{ ...state, refresh, expireSession, updateProfile }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('登录态组件必须位于 AuthProvider 内');
  return value;
}
