import { afterEach, expect, it, vi } from 'vitest';
import { imageFileError, uploadAvatarImage, uploadToStorage, type UploadGrant } from './media';

const grant: UploadGrant = { mediaId: 'id', publicUrl: '/image', upload: { method: 'POST', url: 'https://storage.example', fields: { policy: 'signed', key: 'object' }, expiresAt: '' } };
class FakeXhr {
  static current: FakeXhr;
  status = 200; responseText = ''; withCredentials = false; timeout = 0;
  upload: { onprogress?: (event: { lengthComputable: boolean; loaded: number; total: number }) => void } = {};
  onload?: () => void; onerror?: () => void; ontimeout?: () => void; onabort?: () => void;
  open = vi.fn(); body?: FormData;
  constructor() { FakeXhr.current = this; }
  send(body: FormData) { this.body = body; }
  abort() { this.onabort?.(); }
}
afterEach(() => vi.unstubAllGlobals());
it('头像上传声明AVATAR用途并遵循2 MiB边界，不额外等待或查询状态', async () => {
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ token: 'masked', headerName: 'CUSTOM-CSRF' })))
    .mockResolvedValueOnce(new Response(JSON.stringify(grant)));
  vi.stubGlobal('fetch', fetcher);
  const file = new File(['png'], 'avatar.png', { type: 'image/png' });
  const pending = uploadAvatarImage(file, new AbortController().signal);
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  expect(fetcher.mock.calls[1]).toEqual(['/api/media/uploads', expect.objectContaining({
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CUSTOM-CSRF': 'masked' },
    body: JSON.stringify({ purpose: 'AVATAR', filename: 'avatar.png', contentType: 'image/png', size: 3 }),
  })]);
  await vi.waitFor(() => expect(FakeXhr.current.body).toBeDefined());
  FakeXhr.current.responseText = '{"success":true}'; FakeXhr.current.onload?.();
  await expect(pending).resolves.toEqual({ mediaId: grant.mediaId, publicUrl: grant.publicUrl });
  expect(fetcher).toHaveBeenCalledTimes(2);
  const invalid = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'large.png', { type: 'image/png' });
  await expect(uploadAvatarImage(invalid, new AbortController().signal)).rejects.toThrow();
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it('直传签名字段和文件顺序正确，凭证关闭且成功立即返回，无额外延迟', async () => {
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
  const file = new File(['png'], 'x.png', { type: 'image/png' });
  const progress = vi.fn();
  const pending = uploadToStorage(grant, file, new AbortController().signal, progress);
  const xhr = FakeXhr.current;
  expect([...xhr.body!.keys()]).toEqual(['policy', 'key', 'file']); expect(xhr.withCredentials).toBe(false);
  xhr.upload.onprogress?.({ lengthComputable: true, loaded: 50, total: 100 }); expect(progress).toHaveBeenCalledWith(50);
  xhr.responseText = JSON.stringify({ success: true }); xhr.onload?.();
  await expect(pending).resolves.toEqual({ mediaId: 'id', publicUrl: '/image' });
});
it.each([[200, ''], [200, '<xml/>'], [203, '{"success":true}'], [200, '{"success":false,"code":"MODERATION_REJECTED"}']])('拒绝非成功响应 %s %s', async (status, body) => {
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
  const pending = uploadToStorage(grant, new File(['x'], 'x.png'), new AbortController().signal, vi.fn());
  const assertion = expect(pending).rejects.toThrow();
  FakeXhr.current.status = status; FakeXhr.current.responseText = body; FakeXhr.current.onload?.(); await assertion;
});
it('取消上传结束当前请求，不自动重传；非法大小与类型提前拒绝', async () => {
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
  const controller = new AbortController();
  const pending = uploadToStorage(grant, new File(['x'], 'x.png'), controller.signal, vi.fn());
  const assertion = expect(pending).rejects.toMatchObject({ name: 'AbortError' }); controller.abort(); await assertion;
  expect(imageFileError(new File(['x'], 'x.gif', { type: 'image/gif' }), 5)).toMatch(/仅支持/);
  expect(imageFileError(new File(['xxxxxx'], 'x.png', { type: 'image/png' }), 5)).toMatch(/大小/);
});
