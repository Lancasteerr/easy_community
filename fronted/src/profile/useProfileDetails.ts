import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getPublicProfile } from '../api/client';
import type { PublicUserProfile } from '../api/types';
import { capturePage } from '../auth/pageLifetime';
import { validUserId } from './routes';

interface DetailsState {
  status: 'loading' | 'ready' | 'missing' | 'error';
  profile: PublicUserProfile | null;
  retryAt: number;
}
const initial = (): DetailsState => ({ status: 'loading', profile: null, retryAt: 0 });

/** 主页需要区分用户不存在与读取故障，不改变顶栏和设置页原有的反馈。 */
export function useProfileDetails(userId: string | undefined) {
  const active = capturePage();
  const [state, setState] = useState<DetailsState>(initial);
  const generation = useRef(0);
  const pending = useRef<AbortController | null>(null);
  const retryAt = useRef(0);
  const read = useCallback(async () => {
    if (pending.current || retryAt.current > Date.now()) return;
    if (!validUserId(userId)) { setState({ ...initial(), status: 'missing' }); return; }
    const controller = new AbortController();
    const version = ++generation.current;
    pending.current = controller;
    setState(initial());
    try {
      const profile = await getPublicProfile(userId, controller.signal);
      if (version !== generation.current || !active() || controller.signal.aborted) return;
      setState({ status: 'ready', profile, retryAt: 0 });
    } catch (error) {
      if (version !== generation.current || !active() || controller.signal.aborted) return;
      retryAt.current = error instanceof ApiError ? error.retryAt : 0;
      setState({ status: error instanceof ApiError && error.status === 404 ? 'missing' : 'error', profile: null, retryAt: retryAt.current });
    } finally {
      if (version === generation.current) pending.current = null;
    }
  }, [userId]);
  useEffect(() => {
    retryAt.current = 0;
    void read();
    return () => { generation.current++; pending.current?.abort(); pending.current = null; };
  }, [read]);
  return { ...state, retry: read };
}
