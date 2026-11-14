import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { getAuthorArticles } from '../api/articles';
import { ApiError } from '../api/client';
import type { FeedPage } from '../api/types';
import { article, deferred } from '../test/fixtures';
import { useProfileArticles } from './useProfileArticles';

vi.mock('../api/articles', () => ({ getAuthorArticles: vi.fn() }));
const read = vi.mocked(getAuthorArticles);
beforeEach(() => read.mockReset());

it('等资料成功才启动读取，短页续读并去除页内及跨页重复', async () => {
  read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'a+/=' })
    .mockResolvedValueOnce({ items: [article('1'), article('2'), article('2')], nextCursor: null });
  const { result, rerender } = renderHook(({ enabled }) => useProfileArticles('8', enabled, 'profile:dedupe'), { initialProps: { enabled: false } });
  expect(read).not.toHaveBeenCalled();
  rerender({ enabled: true });
  await waitFor(() => expect(result.current.status).toBe('ready'));
  await act(() => result.current.loadMore());
  expect(read).toHaveBeenLastCalledWith('8', 'a+/=', expect.any(AbortSignal));
  expect(result.current.items.map((item) => item.articleId)).toEqual(['1', '2']);
  await act(() => result.current.loadMore(true));
  expect(read).toHaveBeenCalledTimes(2);
});

it('同步触底互斥，连续三页无新增后允许手动继续', async () => {
  const next = deferred<FeedPage>();
  read.mockResolvedValueOnce({ items: [], nextCursor: 'a' }).mockReturnValueOnce(next.promise)
    .mockResolvedValueOnce({ items: [], nextCursor: 'c' }).mockResolvedValueOnce({ items: [article()], nextCursor: null });
  const { result } = renderHook(() => useProfileArticles('8', true, 'profile:empty'));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  act(() => { void result.current.loadMore(); void result.current.loadMore(); });
  expect(read).toHaveBeenCalledTimes(2);
  await act(async () => next.resolve({ items: [], nextCursor: 'b' }));
  await act(() => result.current.loadMore());
  expect(result.current.paused).toBe(true);
  await act(() => result.current.loadMore());
  expect(read).toHaveBeenCalledTimes(3);
  await act(() => result.current.loadMore(true));
  expect(result.current.items).toHaveLength(1);
});

it.each(['ARTICLE_CURSOR_EXPIRED', 'ARTICLE_SEARCH_INVALID', 'repeated'])('游标 %s 保留内容且只能重开查询', async (code) => {
  read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'a' });
  if (code === 'repeated') read.mockResolvedValueOnce({ items: [], nextCursor: 'a' });
  else read.mockRejectedValueOnce(new ApiError(400, code, '失效'));
  read.mockResolvedValueOnce({ items: [article('2')], nextCursor: null });
  const { result } = renderHook(() => useProfileArticles('8', true, `profile:reset-${code}`));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  await act(() => result.current.loadMore());
  expect(result.current.status).toBe('resetRequired');
  expect(result.current.items[0].articleId).toBe('1');
  await act(() => result.current.loadMore(true));
  expect(read).toHaveBeenCalledTimes(2);
  await act(() => result.current.restart());
  expect(result.current.items[0].articleId).toBe('2');
  expect(result.current.revision).toBe(1);
});

it('追加故障保留条目并使用原游标重试，限流期间禁止重发', async () => {
  const now = Date.now();
  read.mockResolvedValueOnce({ items: [article()], nextCursor: 'a' })
    .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', now + 30000))
    .mockResolvedValueOnce({ items: [], nextCursor: null });
  const { result } = renderHook(() => useProfileArticles('8', true, 'profile:rate'));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  await act(() => result.current.loadMore());
  expect(result.current.status).toBe('appendError');
  expect(result.current.items).toHaveLength(1);
  await act(() => result.current.retry());
  expect(read).toHaveBeenCalledTimes(2);
  const clock = vi.spyOn(Date, 'now').mockReturnValue(now + 30001);
  await act(() => result.current.retry());
  clock.mockRestore();
  expect(read).toHaveBeenLastCalledWith('8', 'a', expect.any(AbortSignal));
});

it('返回历史项恢复列表，作者切换后旧响应不能回填', async () => {
  read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'a' });
  const first = renderHook(() => useProfileArticles('8', true, 'profile:history-8'));
  await waitFor(() => expect(first.result.current.status).toBe('ready'));
  first.unmount();
  const stale = deferred<FeedPage>();
  read.mockReturnValueOnce(stale.promise).mockResolvedValueOnce({ items: [article('2')], nextCursor: null });
  const second = renderHook(({ id }) => useProfileArticles(id, true, `profile:history-${id}`), { initialProps: { id: '8' } });
  expect(second.result.current.items[0].articleId).toBe('1');
  act(() => { void second.result.current.loadMore(); });
  second.rerender({ id: '9' });
  await waitFor(() => expect(second.result.current.items[0]?.articleId).toBe('2'));
  await act(async () => stale.resolve({ items: [article('stale')], nextCursor: null }));
  expect(second.result.current.items[0].articleId).toBe('2');
});
