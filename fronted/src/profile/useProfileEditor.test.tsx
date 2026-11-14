import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, expect, it, vi } from 'vitest';
import { getSession } from '../api/client';
import { updateProfileBio } from '../api/profile';
import { AuthProvider, useAuth } from '../auth/AuthContext';
import { renewPage } from '../auth/pageLifetime';
import { documentNavigation } from '../navigation/documentNavigation';
import { deferred, profile } from '../test/fixtures';
import { useProfileEditor } from './useProfileEditor';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getSession: vi.fn() }));
vi.mock('../api/profile', () => ({ updateProfileBio: vi.fn(), updateProfileAvatar: vi.fn() }));
const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
beforeEach(() => {
  vi.mocked(getSession).mockReset().mockResolvedValue(profile);
  vi.mocked(updateProfileBio).mockReset();
});

it('资料保存成功只刷新页面，不取消独立的会话复核', async () => {
  const write = deferred<typeof profile>();
  const session = deferred<typeof profile>();
  vi.mocked(updateProfileBio).mockReturnValue(write.promise);
  vi.mocked(getSession).mockResolvedValueOnce(profile).mockReturnValueOnce(session.promise);
  const { result, unmount } = renderHook(() => ({ auth: useAuth(), editor: useProfileEditor(profile) }), { wrapper });
  await waitFor(() => expect(result.current.auth.status).toBe('authenticated'));
  act(() => result.current.editor.editBio('新简介'));
  let saving!: Promise<void>;
  act(() => { saving = result.current.editor.saveBio(); });
  act(() => { void result.current.auth.refresh(); });
  await act(async () => { write.resolve({ ...profile, bio: '新简介' }); await saving; });
  expect(documentNavigation.reload).toHaveBeenCalledTimes(1);
  expect(vi.mocked(getSession).mock.calls[1][0]?.aborted).toBe(false);
  expect(result.current.auth.refreshing).toBe(true);
  unmount();
  await act(async () => session.resolve(profile));
});

it('旧页面的资料写入响应不能刷新新会话页面', async () => {
  const write = deferred<typeof profile>();
  vi.mocked(updateProfileBio).mockReturnValue(write.promise);
  const { result } = renderHook(() => ({ auth: useAuth(), editor: useProfileEditor(profile) }), { wrapper });
  await waitFor(() => expect(result.current.auth.status).toBe('authenticated'));
  act(() => result.current.editor.editBio('旧输入'));
  let saving!: Promise<void>;
  act(() => { saving = result.current.editor.saveBio(); });
  renewPage();
  await act(async () => { write.resolve(profile); await saving; });
  expect(documentNavigation.reload).not.toHaveBeenCalled();
});
