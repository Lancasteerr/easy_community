import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getProfile } from '../api/client';
import type { UserProfile } from '../api/types';
import { deferred, profile } from '../test/fixtures';
import { AuthProvider, useAuth } from './AuthContext';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getProfile: vi.fn() }));
const read = vi.mocked(getProfile);
const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
beforeEach(() => { read.mockReset(); });

describe('后台身份复核', () => {
  it('资料写入只合并对应字段，并隔离已经在途的旧读取', async () => {
    const pending = deferred<UserProfile>();
    read.mockResolvedValueOnce(profile).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { void result.current.refresh(); });
    act(() => { expect(result.current.updateProfile(profile.userId, { bio: '新简介' })).toBe(true); });
    expect(read.mock.calls[1][0]?.aborted).toBe(true);
    await act(async () => pending.resolve(profile));
    expect(result.current.user).toEqual({ ...profile, bio: '新简介' });
    act(() => { result.current.updateProfile(profile.userId, { avatarMediaId: 'avatar-id', avatarUrl: '/avatar.jpg' }); });
    expect(result.current.user?.bio).toBe('新简介');
    expect(result.current.user?.avatarUrl).toBe('/avatar.jpg');
  });

  it('其他账号或已失效会话不能接受迟到的资料写入响应', async () => {
    read.mockResolvedValue(profile);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { expect(result.current.updateProfile('another-user', { bio: '其他账号' })).toBe(false); });
    expect(result.current.user).toEqual(profile);
    act(() => result.current.expireSession());
    act(() => { expect(result.current.updateProfile(profile.userId, { bio: '迟到结果' })).toBe(false); });
    expect(result.current.user).toBeNull();
  });

  it('同账号复核及非401错误保留已确认资料，不冒充游客', async () => {
    const pending = deferred<UserProfile>();
    read.mockResolvedValueOnce(profile).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { void result.current.refresh(); });
    expect(result.current.status).toBe('authenticated');
    expect(result.current.refreshing).toBe(true);
    await act(async () => pending.reject(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败')));
    expect(result.current.user).toEqual(profile);
    expect(result.current.status).toBe('authenticated');
    expect(result.current.error).toBeInstanceOf(ApiError);
  });

  it('业务401作废在途资料，即使服务忽略取消也不能恢复旧身份', async () => {
    const pending = deferred<UserProfile>();
    read.mockResolvedValueOnce(profile).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useAuth, { wrapper });
    await waitFor(() => expect(result.current.status).toBe('authenticated'));
    act(() => { void result.current.refresh(); });
    act(() => result.current.expireSession());
    expect(read.mock.calls[1][0]?.aborted).toBe(true);
    await act(async () => pending.resolve(profile));
    expect(result.current.status).toBe('guest');
    expect(result.current.user).toBeNull();
    expect(result.current.endReason).toBe('action');
  });

  it('主动登出作废迟到的资料响应，后台游客复核具有独立原因', async () => {
    const pending = deferred<UserProfile>();
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
