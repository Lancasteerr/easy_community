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
  });
});
