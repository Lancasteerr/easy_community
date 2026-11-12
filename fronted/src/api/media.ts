import { ApiError, getCsrf, request } from './client';

export interface UploadedMedia { mediaId: string; publicUrl: string }
export interface UploadGrant extends UploadedMedia {
  upload: { method: string; url: string; fields: Record<string, string>; expiresAt: string };
}
const allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];
export function imageFileError(file: File, maximum: number) {
  if (!allowedTypes.includes(file.type)) return '仅支持 JPG、PNG、WebP 图片';
  if (!file.size || file.size > maximum) return `图片大小需在 0～${maximum / 1024 / 1024}MiB 之间`;
  return '';
}

/** XHR 仅用于直传进度；不自动重试授权或上传，也不额外等待媒体发布。 */
export function uploadToStorage(grant: UploadGrant, file: File, signal: AbortSignal, progress: (value: number) => void): Promise<UploadedMedia> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new DOMException('上传已取消', 'AbortError')); return; }
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const finish = () => signal.removeEventListener('abort', abort);
    xhr.open(grant.upload.method, grant.upload.url);
    xhr.withCredentials = false;
    xhr.timeout = 120_000;
    xhr.upload.onprogress = (event) => { if (event.lengthComputable) progress(Math.min(100, Math.round(event.loaded / event.total * 100))); };
    xhr.onload = () => {
      finish();
      let result: { success?: boolean; stage?: string; code?: string } = {};
      try { result = JSON.parse(xhr.responseText); } catch { /* XML、空响应均不能当成成功。 */ }
      if (xhr.status === 200 && result.success === true) resolve({ mediaId: grant.mediaId, publicUrl: grant.publicUrl });
      else reject(new ApiError(xhr.status, result.code ?? 'STORAGE_UPLOAD_FAILED', '图片上传失败，请重试'));
    };
    xhr.onerror = xhr.ontimeout = () => { finish(); reject(new ApiError(0, 'STORAGE_UPLOAD_FAILED', '图片上传失败，请检查网络后重试')); };
    xhr.onabort = () => { finish(); reject(new DOMException('上传已取消', 'AbortError')); };
    signal.addEventListener('abort', abort, { once: true });
    const body = new FormData();
    Object.entries(grant.upload.fields).forEach(([key, value]) => body.append(key, value));
    body.append('file', file);
    xhr.send(body);
  });
}
export async function uploadArticleImage(file: File, maximum: number, signal: AbortSignal, progress: (value: number) => void) {
  const invalid = imageFileError(file, maximum);
  if (invalid) throw new Error(invalid);
  const csrf = await getCsrf(signal);
  const grant = await request<UploadGrant>('/api/media/uploads', { method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ purpose: 'ARTICLE_IMAGE', filename: file.name, contentType: file.type, size: file.size }) });
  return uploadToStorage(grant, file, signal, progress);
}
export function uploadError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 429) return '上传过于频繁，请稍后重试';
    if (error.code.startsWith('MODERATION_')) return '图片未通过审核，请更换图片';
    if (error.status === 403) return '上传凭证已失效，请重新上传';
    return '图片上传失败，请重新上传';
  }
  return error instanceof Error ? error.message : '图片上传失败，请重新上传';
}
