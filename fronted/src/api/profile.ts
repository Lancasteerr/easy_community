import { getCsrf, request } from './client';
import { uploadAvatarImage } from './media';
import type { UserProfile } from './types';

/** 简介只走文字资料入口，不携带身份、头像或其他未开放字段。 */
export async function updateProfileBio(bio: string, signal: AbortSignal) {
  const csrf = await getCsrf(signal);
  return request<UserProfile>('/api/users/edit', {
    method: 'PATCH', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ bio }),
  });
}

/** 直传和绑定分步确认；不等待READY、不轮询，也不自动重放写入。 */
export async function updateProfileAvatar(file: File, signal: AbortSignal) {
  const uploaded = await uploadAvatarImage(file, signal);
  const csrf = await getCsrf(signal);
  return request<UserProfile>('/api/users/me/avatar', {
    method: 'PUT', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ mediaId: uploaded.mediaId }),
  });
}
