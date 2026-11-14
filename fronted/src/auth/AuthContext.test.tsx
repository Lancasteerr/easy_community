import { documentNavigation } from '../navigation/documentNavigation';
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
    await act(async () => { await result.current.refresh(); });
    expect(result.current.endReason).toBe('logout');
  });
});

it('首次游客不会触发整页刷新，身份服务失败也不会冒充游客', async () => {
  read.mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客'));
  const first = renderHook(useAuth, { wrapper });
  await waitFor(() => expect(first.result.current.status).toBe('guest'));
  expect(documentNavigation.reload).not.toHaveBeenCalled();
  first.unmount();
  read.mockRejectedValue(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '内部错误'));
  const next = renderHook(useAuth, { wrapper });
  await waitFor(() => expect(next.result.current.status).toBe('error'));
  expect(next.result.current.identity).toBeNull();
  expect(documentNavigation.reload).not.toHaveBeenCalled();
});

it('后台识别另一账号后进入显式登录', async () => {
  window.history.replaceState(null, '', '/manage/articles');
  read.mockResolvedValueOnce(profile).mockResolvedValueOnce({ userId: '8', role: 'USER' });
  const { result, unmount } = renderHook(useAuth, { wrapper });
  await waitFor(() => expect(result.current.status).toBe('authenticated'));
  await act(async () => { await result.current.refresh(); });
  expect(result.current.resetting).toBe(true);
  expect(documentNavigation.replace).toHaveBeenCalledExactlyOnceWith('/login?returnTo=%2Fmanage%2Farticles&reauth=1');
  unmount();
});

it('组件卸载后丢弃迟到会话响应，不执行导航', async () => {
  const pending = deferred<SessionIdentity>();
  read.mockReturnValue(pending.promise);
  const { unmount } = renderHook(useAuth, { wrapper });
  unmount();
  await act(async () => pending.resolve(profile));
  expect(documentNavigation.replace).not.toHaveBeenCalled();
  expect(documentNavigation.reload).not.toHaveBeenCalled();
});

it('浏览器往返缓存恢复旧文档时重新加载，不复用原账号画面', async () => {
  read.mockResolvedValue(profile);
  renderHook(useAuth, { wrapper });
  await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
  act(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
  expect(documentNavigation.reload).toHaveBeenCalledTimes(1);
});
