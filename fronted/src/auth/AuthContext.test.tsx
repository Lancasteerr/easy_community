import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getSession } from '../api/client';
import type { SessionIdentity } from '../api/types';
import { deferred, profile } from '../test/fixtures';
import { AuthProvider, useAuth } from './AuthContext';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getSession: vi.fn() }));
const read = vi.mocked(getSession);
const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
beforeEach(() => { read.mockReset(); });

describe('后台身份复核', () => {
  it('登录结果只提取身份并取消旧会话读取，迟到401不能撤销新登录', async () => {
    const pending = deferred<SessionIdentity>();
    read.mockReturnValue(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    act(() => result.current.acceptSession(profile));
    expect(result.current.identity).toEqual({ userId: profile.userId, role: profile.role });
    expect(read.mock.calls[0][0]?.aborted).toBe(true);
    await act(async () => pending.reject(new ApiError(401, 'AUTHENTICATION_REQUIRED', '旧会话')));
    expect(result.current.status).toBe('authenticated');
  });

  it('同账号复核及非401错误保留已确认会话，不冒充游客', async () => {
    const pending = deferred<SessionIdentity>();
    read.mockResolvedValueOnce(profile).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { void result.current.refresh(); });
    expect(result.current.status).toBe('authenticated');
    expect(result.current.refreshing).toBe(true);
    await act(async () => pending.reject(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败')));
    expect(result.current.identity).toEqual({ userId: profile.userId, role: profile.role });
    expect(result.current.status).toBe('authenticated');
    expect(result.current.error).toBeInstanceOf(ApiError);
  });

  it('业务401作废在途会话，即使服务忽略取消也不能恢复旧身份', async () => {
    const pending = deferred<SessionIdentity>();
    read.mockResolvedValueOnce(profile).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { void result.current.refresh(); });
    act(() => result.current.expireSession());
    expect(read.mock.calls[1][0]?.aborted).toBe(true);
    await act(async () => pending.resolve(profile));
    expect(result.current.status).toBe('guest');
    expect(result.current.identity).toBeNull();
    expect(result.current.endReason).toBe('action');
  });

  it('主动登出作废迟到的会话响应，后台游客复核具有独立原因', async () => {
    const pending = deferred<SessionIdentity>();
    read.mockResolvedValueOnce(profile).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { void result.current.refresh(); });
    act(() => result.current.expireSession('logout'));
    await act(async () => pending.resolve(profile));
    expect(result.current.status).toBe('guest'); expect(result.current.endReason).toBe('logout');
    read.mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客'));
    await act(async () => { await result.current.refresh(); });
    expect(result.current.endReason).toBe('verification');
  });
});
