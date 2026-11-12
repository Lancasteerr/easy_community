import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getCsrf } from '../api/client';
import { saveArticle } from '../api/articles';
import { useArticleSave } from './useArticleSave';
import { emptyForm } from './model';
import { deferred } from '../test/fixtures';

vi.mock('../api/articles', () => ({ saveArticle: vi.fn() }));
vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getCsrf: vi.fn() }));
const valid = () => ({ ...emptyForm(), title: '标题', content: { schemaVersion: 1 as const, doc: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '正文' }] }] } } });
beforeEach(() => { vi.mocked(getCsrf).mockReset().mockResolvedValue({ token: 'masked', headerName: 'X-CSRF-TOKEN' }); vi.mocked(saveArticle).mockReset(); });
it('校验失败及上传未结束时不申请凭证、不写入', async () => {
  const { result } = renderHook(() => useArticleSave(vi.fn(), vi.fn()));
  await act(async () => { await result.current.submit(emptyForm(), undefined, false); });
  expect(result.current.errors).toMatchObject({ title: '请输入文章标题', content: '请输入文章正文' });
  await act(async () => { await result.current.submit(valid(), undefined, true); });
  expect(getCsrf).not.toHaveBeenCalled(); expect(saveArticle).not.toHaveBeenCalled();
});
it('提交互斥，成功仅通知一次并保留操作模式', async () => {
  const pending = deferred<{ articleId: string }>(); vi.mocked(saveArticle).mockReturnValue(pending.promise);
  const saved = vi.fn(); const { result } = renderHook(() => useArticleSave(saved, vi.fn()));
  let operation: Promise<void>;
  await act(async () => { operation = result.current.submit(valid(), undefined, false); });
  expect(result.current.dialog?.status).toBe('pending');
  await act(async () => { await result.current.submit(valid(), undefined, false); });
  expect(saveArticle).toHaveBeenCalledTimes(1);
  await act(async () => { pending.resolve({ articleId: '12' }); await operation; });
  expect(saved).toHaveBeenCalledWith('12', valid()); expect(result.current.dialog).toMatchObject({ status: 'success', editing: false });
});
it.each(['ARTICLE_WRITE_RESULT_UNKNOWN', 'NETWORK_ERROR', 'MEDIA_STATE_CONFLICT'])('失败 %s 保留单一失败态，只有人工重试再次发送', async (code) => {
  vi.mocked(saveArticle).mockRejectedValueOnce(new ApiError(code === 'NETWORK_ERROR' ? 0 : code === 'MEDIA_STATE_CONFLICT' ? 409 : 503, code, 'failed')).mockResolvedValue({ articleId: '12' });
  const saved = vi.fn(); const { result } = renderHook(() => useArticleSave(saved, vi.fn()));
  await act(async () => { await result.current.submit(valid(), '12', false); });
  expect(result.current.dialog?.status).toBe('failure'); expect(saveArticle).toHaveBeenCalledTimes(1); expect(saved).not.toHaveBeenCalled();
  await act(async () => { await result.current.submit(valid(), '12', false); });
  expect(saveArticle).toHaveBeenCalledTimes(2); expect(result.current.dialog).toMatchObject({ status: 'success', editing: true });
});
it('前置凭证失败不写入；401通知登录流程且不重发', async () => {
  vi.mocked(getCsrf).mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', 'expired'));
  const unauthorized = vi.fn(); const { result } = renderHook(() => useArticleSave(vi.fn(), unauthorized));
  await act(async () => { await result.current.submit(valid(), '12', false); });
  expect(saveArticle).not.toHaveBeenCalled(); expect(unauthorized).toHaveBeenCalledTimes(1);
});
