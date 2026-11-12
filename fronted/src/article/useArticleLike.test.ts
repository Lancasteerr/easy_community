import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { getCsrf, ApiError } from '../api/client';
import { setArticleLike } from '../api/articles';
import type { ArticleDetailData, ArticleLikeAccepted } from '../api/types';
import { article, deferred } from '../test/fixtures';
import { useArticleLike } from './useArticleLike';

vi.mock('../api/articles', () => ({ setArticleLike: vi.fn() }));
vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getCsrf: vi.fn() }));
const write = vi.mocked(setArticleLike);
const credentials = vi.mocked(getCsrf);
const notify = vi.fn();
const unauthorized = vi.fn();
const token = { token: 'token', headerName: 'X-CSRF-TOKEN' };
const data = (id = '1'): ArticleDetailData => ({ ...article(id), likedByMe: false, likeCount: 128, coverMediaId: null, contentHtml: '<p>正文</p>' });
const options = { enabled: true, userId: 'user', notify, onUnauthorized: unauthorized };
beforeEach(() => {
  credentials.mockReset().mockResolvedValue(token);
  write.mockReset().mockImplementation(async (_id, liked, operationId) => ({ accepted: true, liked, operationId, version: '9007199254740995' }));
  notify.mockReset(); unauthorized.mockReset();
});

it('防止凭证读取和写入阶段重复点击，仅在202后更新数量，支持取消', async () => {
  const pending = deferred<ArticleLikeAccepted>();
  write.mockReturnValueOnce(pending.promise);
  const { result } = renderHook(() => useArticleLike(data(), options));
  await act(async () => { void result.current.toggle(); void result.current.toggle(); });
  expect(write).toHaveBeenCalledTimes(1);
  expect(credentials).toHaveBeenCalledTimes(1);
  expect(result.current).toMatchObject({ count: 128, liked: false, busy: true });
  const operation = write.mock.calls[0][2];
  expect(operation).toMatch(/^[0-9a-f-]{36}$/);
  await act(async () => pending.resolve({ accepted: true, operationId: operation, liked: true, version: '9007199254740995' }));
  expect(result.current).toMatchObject({ count: 129, liked: true, busy: false });
  await act(() => result.current.toggle());
  expect(result.current).toMatchObject({ count: 128, liked: false, busy: false });
  expect(write.mock.calls[1][1]).toBe(false);
  expect(write.mock.calls[1][2]).not.toBe(operation);
});

it.each([
  new ApiError(0, 'REQUEST_TIMEOUT', '超时'),
  new ApiError(0, 'NETWORK_ERROR', '断网'),
  new ApiError(503, 'LIKE_WRITE_RESULT_UNKNOWN', '结果未知'),
])('写入结果不确定时保留状态，不通知、不重试，并可接受新的手动操作：%s', async (error) => {
  write.mockRejectedValueOnce(error);
  const { result } = renderHook(() => useArticleLike(data(), options));
  await act(() => result.current.toggle());
  expect(result.current).toMatchObject({ count: 128, liked: false, busy: false });
  expect(notify).not.toHaveBeenCalled();
  expect(write).toHaveBeenCalledTimes(1);
  await act(() => result.current.toggle());
  expect(write).toHaveBeenCalledTimes(2);
  expect(write.mock.calls[1][2]).not.toBe(write.mock.calls[0][2]);
});

it('切换文章后，旧点赞响应即使忽略取消也不能污染新文章', async () => {
  const pending = deferred<ArticleLikeAccepted>();
  write.mockReturnValueOnce(pending.promise);
  const { result, rerender } = renderHook(({ id }) => useArticleLike(data(id), options), { initialProps: { id: '1' } });
  await act(async () => { void result.current.toggle(); });
  const operation = write.mock.calls[0][2];
  const signal = write.mock.calls[0][4]!;
  rerender({ id: '2' });
  expect(signal.aborted).toBe(true);
  await act(async () => pending.resolve({ accepted: true, operationId: operation, liked: true, version: '1' }));
  expect(result.current).toMatchObject({ count: 128, liked: false, busy: false });
});

it('403只刷新凭证，不重发点赞；下一次手动操作使用新凭证', async () => {
  credentials.mockResolvedValueOnce(token).mockResolvedValue({ ...token, token: 'fresh' });
  write.mockRejectedValueOnce(new ApiError(403, 'ACCESS_OR_CSRF_DENIED', '凭证失效'));
  const { result } = renderHook(() => useArticleLike(data(), options));
  await act(() => result.current.toggle());
  expect(write).toHaveBeenCalledTimes(1);
  expect(credentials).toHaveBeenCalledTimes(2);
  expect(notify).toHaveBeenCalledWith('操作凭证已刷新，请再次点击');
  await act(() => result.current.toggle());
  expect(write.mock.calls[1][3].token).toBe('fresh');
});

it('429在重试期限内暂停，401上报身份失效', async () => {
  write.mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', Date.now() + 30_000));
  const first = renderHook(() => useArticleLike(data(), options));
  await act(() => first.result.current.toggle());
  await act(() => first.result.current.toggle());
  expect(write).toHaveBeenCalledTimes(1);
  first.unmount();
  write.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '身份失效'));
  const second = renderHook(() => useArticleLike(data(), options));
  await act(() => second.result.current.toggle());
  expect(unauthorized).toHaveBeenCalledTimes(1);
});
