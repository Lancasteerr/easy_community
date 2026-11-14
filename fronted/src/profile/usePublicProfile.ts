import { useEffect, useRef, useState } from 'react';
import { capturePage } from '../auth/pageLifetime';
import { isAuthenticationFailure, getPublicProfile } from '../api/client';
import type { PublicUserProfile } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/Toast';

/** 仅保留当前组件的展示数据；请求取消和代次共同阻止旧账号响应回填。 */
export function usePublicProfile(userId: string | undefined) {
  const pageActive = capturePage();
  const auth = useAuth();
  const notify = useToast();
  const generation = useRef(0);
  const [state, setState] = useState<{ owner?: string; profile: PublicUserProfile | null; failed: boolean }>({ profile: null, failed: false });
  useEffect(() => {
    const version = ++generation.current;
    const controller = new AbortController();
    setState({ owner: userId, profile: null, failed: false });
    if (userId) void getPublicProfile(userId, controller.signal).then((profile) => {
      if (version !== generation.current || !pageActive() || controller.signal.aborted) return;
      setState({ owner: userId, profile, failed: false });
    }).catch((error) => {
      if (version !== generation.current || !pageActive() || controller.signal.aborted) return;
      if (isAuthenticationFailure(error)) { auth.expireSession(); return; }
      setState({ owner: userId, profile: null, failed: true });
      notify('加载失败，请稍后重试');
    });
    return () => { generation.current++; controller.abort(); };
  }, [userId, auth.expireSession, notify]);
  return state.owner === userId ? state : { profile: null, failed: false };
}
