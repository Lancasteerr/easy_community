import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { updateProfileAvatar, updateProfileBio } from '../api/profile';
import type { UserProfile } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/Toast';
import { useCooldown } from '../hooks/useCooldown';

/** 与后端单行文本限制一致，允许emoji及其组合符号。 */
const forbiddenBioCharacters = /[\u0000-\u001F\u007F-\u009F\u2028\u2029]/u;

/** 每个账号只建立一次草稿；后台复核和头像更新不重置未保存的简介。 */
export function useProfileEditor(profile: UserProfile) {
  const auth = useAuth();
  const notify = useToast();
  const [bio, setBio] = useState(profile.bio);
  const [busy, setBusy] = useState(false);
  const [retryAt, setRetryAt] = useState(0);
  const savedBio = useRef(profile.bio);
  const pending = useRef<AbortController | null>(null);
  const queuedAvatar = useRef<File | null>(null);
  const cooldown = useRef(0);
  const current = useRef(auth); current.current = auth;
  const seconds = useCooldown(retryAt);
  const count = Array.from(bio).length;
  const enabled = auth.status === 'authenticated' && !auth.refreshing && !auth.error;

  useEffect(() => () => { pending.current?.abort(); queuedAvatar.current = null; }, []);

  function feedback(message: string) {
    notify(message, { placement: 'top', durationMs: 2500 });
  }

  function editBio(value: string) {
    if (forbiddenBioCharacters.test(value)) { feedback('更新未成功，请重试'); return; }
    setBio(value);
  }

  /** 粘贴前校验原文，防止原生单行输入框静默吞掉换行。 */
  function acceptsPaste(value: string) {
    if (!forbiddenBioCharacters.test(value)) return true;
    feedback('更新未成功，请重试');
    return false;
  }

  function begin() {
    if (pending.current || !enabled || cooldown.current > Date.now()) return null;
    const controller = new AbortController();
    pending.current = controller; setBusy(true);
    return controller;
  }

  function isCurrent(controller: AbortController) {
    const latest = current.current;
    return pending.current === controller && !controller.signal.aborted
      && latest.status === 'authenticated' && latest.user?.userId === profile.userId;
  }

  function failed(error: unknown, controller: AbortController) {
    if (!isCurrent(controller)) return;
    const deadline = error instanceof ApiError ? error.retryAt : 0;
    cooldown.current = deadline; setRetryAt(deadline);
    feedback('更新未成功，请重试');
    if (error instanceof ApiError && error.status === 401 && error.code !== 'STORAGE_UPLOAD_FAILED') {
      current.current.expireSession('action');
    }
  }

  function finish(controller: AbortController) {
    if (pending.current !== controller) return;
    pending.current = null;
    if (!controller.signal.aborted) setBusy(false);
  }

  async function saveBio() {
    if (count > 50 || bio === savedBio.current) return;
    const controller = begin();
    if (!controller) return;
    try {
      const result = await updateProfileBio(bio, controller.signal);
      if (!isCurrent(controller) || !current.current.updateProfile(profile.userId, { bio: result.bio })) return;
      savedBio.current = result.bio; setBio(result.bio);
      feedback('资料已保存');
    } catch (error) { failed(error, controller); }
    finally { finish(controller); }
  }

  async function changeAvatar(file: File) {
    // 选择器返回的focus可能先触发身份复核，暂存文件，确认仍为同账号后再上传。
    if (current.current.refreshing) { queuedAvatar.current = file; return; }
    const controller = begin();
    if (!controller) return;
    try {
      const result = await updateProfileAvatar(file, controller.signal);
      if (!isCurrent(controller) || !current.current.updateProfile(profile.userId, {
        avatarMediaId: result.avatarMediaId, avatarUrl: result.avatarUrl,
      })) return;
      feedback('头像已更新');
    } catch (error) { failed(error, controller); }
    finally { finish(controller); }
  }

  useEffect(() => {
    if (auth.refreshing || !queuedAvatar.current) return;
    const file = queuedAvatar.current; queuedAvatar.current = null;
    if (enabled && auth.user?.userId === profile.userId) void changeAvatar(file);
    else feedback('更新未成功，请重试');
  }, [auth.refreshing, auth.error, auth.user?.userId]);

  return { bio, count, busy, blocked: !enabled || busy || seconds > 0,
    // 仅窗口复核时保留原生文件change事件；可见按钮仍暂停新操作。
    fileBlocked: auth.status !== 'authenticated' || !!auth.error || busy || seconds > 0,
    editBio, acceptsPaste, saveBio, changeAvatar };
}
