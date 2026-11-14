import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getPublicProfile } from '../api/client';
import { deferred, profile } from '../test/fixtures';
import { useProfileDetails } from './useProfileDetails';
import { validUserId } from './routes';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getPublicProfile: vi.fn() }));
beforeEach(() => vi.mocked(getPublicProfile).mockReset());

it('非法用户标识不读取资料，正 BIGINT 字符串不会丢失精度', () => {
  for (const id of ['0', '-1', '01', '9223372036854775808', '1/edit']) expect(validUserId(id)).toBe(false);
  expect(validUserId('9223372036854775807')).toBe(true);
  const { result } = renderHook(() => useProfileDetails('0'));
  expect(result.current.status).toBe('missing');
  expect(getPublicProfile).not.toHaveBeenCalled();
});

it('区分不存在与服务故障，手动重试后恢复真实资料', async () => {
  vi.mocked(getPublicProfile).mockRejectedValueOnce(new ApiError(404, 'USER_NOT_FOUND', '不存在'))
    .mockRejectedValueOnce(new ApiError(503, 'INTERNAL_ERROR', '故障')).mockResolvedValueOnce(profile);
  const { result } = renderHook(() => useProfileDetails(profile.userId));
  await waitFor(() => expect(result.current.status).toBe('missing'));
  await act(() => result.current.retry());
  expect(result.current.status).toBe('error');
  await act(() => result.current.retry());
  expect(result.current.profile).toEqual(profile);
});

it('目标切换和卸载取消读取，忽略不遵守取消的旧响应', async () => {
  const stale = deferred<typeof profile>();
  vi.mocked(getPublicProfile).mockReturnValueOnce(stale.promise).mockResolvedValueOnce({ ...profile, userId: '8' });
  const { result, rerender, unmount } = renderHook(({ id }) => useProfileDetails(id), { initialProps: { id: profile.userId } });
  rerender({ id: '8' });
  await waitFor(() => expect(result.current.profile?.userId).toBe('8'));
  await act(async () => stale.resolve(profile));
  expect(result.current.profile?.userId).toBe('8');
  vi.mocked(getPublicProfile).mockReturnValueOnce(new Promise(() => {}));
  rerender({ id: '9' });
  unmount();
  expect(vi.mocked(getPublicProfile).mock.calls[0][1]?.aborted).toBe(true);
  expect(vi.mocked(getPublicProfile).mock.calls.at(-1)?.[1]?.aborted).toBe(true);
});
