import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getPublicProfile, getSession } from '../api/client';
import { AuthProvider, useAuth } from '../auth/AuthContext';
import { deferred, profile } from '../test/fixtures';
import { usePublicProfile } from './usePublicProfile';

const { notify } = vi.hoisted(() => ({ notify: vi.fn() }));
vi.mock('../components/Toast', () => ({ useToast: () => notify }));
vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getSession: vi.fn(), getPublicProfile: vi.fn() }));
const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
beforeEach(() => {
  vi.mocked(getSession).mockReset().mockResolvedValue(profile);
  vi.mocked(getPublicProfile).mockReset(); notify.mockReset();
});

it('资料故障只显示统一提示，不改变会话或自动重试', async () => {
  vi.mocked(getPublicProfile).mockRejectedValue(new ApiError(503, 'INTERNAL_ERROR', '内部连接秘密'));
  const { result } = renderHook(() => ({ auth: useAuth(), data: usePublicProfile(profile.userId) }), { wrapper });
  await waitFor(() => expect(result.current.data.failed).toBe(true));
  expect(result.current.auth.status).toBe('authenticated');
  expect(result.current.auth.error).toBeNull();
  expect(notify).toHaveBeenCalledExactlyOnceWith('加载失败，请稍后重试');
  expect(getPublicProfile).toHaveBeenCalledTimes(1);
});

it('切换目标和卸载后丢弃迟到成功与失败，不弹出旧提示', async () => {
  const old = deferred<typeof profile>();
  const next = deferred<typeof profile>();
  vi.mocked(getPublicProfile).mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise);
  const { result, rerender, unmount } = renderHook(({ id }) => usePublicProfile(id), { wrapper, initialProps: { id: profile.userId } });
  rerender({ id: '8' });
  await act(async () => old.resolve(profile));
  expect(result.current.profile).toBeNull();
  unmount();
  await act(async () => next.reject(new Error('旧错误')));
  expect(notify).not.toHaveBeenCalled();
  expect(vi.mocked(getPublicProfile).mock.calls.every((call) => call[1]?.aborted)).toBe(true);
});
