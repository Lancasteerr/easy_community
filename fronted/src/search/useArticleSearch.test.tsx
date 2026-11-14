import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { FeedPage } from '../api/types';
import { article, deferred } from '../test/fixtures';
import { searchArticles } from './api';
import { useArticleSearch } from './useArticleSearch';
import type { SearchSort } from './routes';

vi.mock('./api', () => ({ searchArticles: vi.fn() }));
const read = vi.mocked(searchArticles);
beforeEach(() => { read.mockReset(); });
afterEach(() => vi.restoreAllMocks());

it('短页继续使用原条件续页，同步触底只发一条请求，追加保持顺序并去重', async () => {
  const next = deferred<FeedPage>();
  read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'next' }).mockReturnValueOnce(next.promise);
  const { result } = renderHook(() => useArticleSearch('社区', 'latest', 'search-lock'));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  act(() => { void result.current.loadMore(); void result.current.loadMore(); });
  expect(read).toHaveBeenCalledTimes(2);
  expect(read).toHaveBeenLastCalledWith('社区', 'latest', 'next', expect.any(AbortSignal));
  await act(async () => next.resolve({ items: [article('1'), article('2'), article('2')], nextCursor: null }));
  expect(result.current.items.map((item) => item.articleId)).toEqual(['1', '2']);
  await act(() => result.current.loadMore(true));
  expect(read).toHaveBeenCalledTimes(2);
});

it('关键词或排序变化取消旧请求，迟到响应不能覆盖新条件', async () => {
  const stale = deferred<FeedPage>();
  read.mockReturnValueOnce(stale.promise).mockResolvedValue({ items: [article('new')], nextCursor: null });
  const { result, rerender } = renderHook(({ q, sort, key }) => useArticleSearch(q, sort, key), {
    initialProps: { q: '旧词', sort: 'relevance' as SearchSort, key: 'search-old' },
  });
  const oldSignal = read.mock.calls[0][3]!;
  rerender({ q: '新词', sort: 'latest', key: 'search-new' });
  await waitFor(() => expect(result.current.items[0]?.articleId).toBe('new'));
  expect(oldSignal.aborted).toBe(true);
  await act(async () => stale.resolve({ items: [article('stale')], nextCursor: 'stale' }));
  expect(result.current.items[0].articleId).toBe('new');
  expect(result.current.nextCursor).toBeNull();
});

it('续页失败保留内容和游标，手动重试前不自动重发；429 到期前禁止重试', async () => {
  const now = Date.now();
  read.mockResolvedValueOnce({ items: [article()], nextCursor: 'same' })
    .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', now + 2000))
    .mockResolvedValueOnce({ items: [article('2')], nextCursor: null });
  const { result } = renderHook(() => useArticleSearch('社区', 'relevance', 'search-retry'));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  await act(() => result.current.loadMore());
  expect(result.current.status).toBe('error');
  expect(result.current.items).toHaveLength(1);
  await act(() => result.current.loadMore(true));
  expect(read).toHaveBeenCalledTimes(2);
  vi.spyOn(Date, 'now').mockReturnValue(now + 2001);
  await act(() => result.current.loadMore());
  expect(read).toHaveBeenCalledTimes(2);
  await act(() => result.current.loadMore(true));
  expect(read).toHaveBeenLastCalledWith('社区', 'relevance', 'same', expect.any(AbortSignal));
  expect(result.current.items).toHaveLength(2);
});

it.each(['ARTICLE_CURSOR_EXPIRED', 'ARTICLE_SEARCH_INVALID', 'repeated'])('失效或重复游标 %s 保留内容，只有重新加载才重开首屏', async (code) => {
  read.mockResolvedValueOnce({ items: [article('old')], nextCursor: 'cursor' });
  if (code === 'repeated') read.mockResolvedValueOnce({ items: [], nextCursor: 'cursor' });
  else read.mockRejectedValueOnce(new ApiError(code === 'ARTICLE_CURSOR_EXPIRED' ? 409 : 400, code, '游标错误'));
  read.mockResolvedValueOnce({ items: [article('fresh')], nextCursor: null });
  const { result } = renderHook(() => useArticleSearch('社区', 'relevance', `search-reset-${code}`));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  await act(() => result.current.loadMore());
  expect(result.current.status).toBe('resetRequired');
  expect(result.current.items[0].articleId).toBe('old');
  await act(() => result.current.loadMore(true));
  expect(read).toHaveBeenCalledTimes(2);
  act(() => result.current.restart());
  await waitFor(() => expect(result.current.items[0]?.articleId).toBe('fresh'));
  expect(read).toHaveBeenLastCalledWith('社区', 'relevance', null, expect.any(AbortSignal));
});

it('连续三页无新增内容后暂停，手动继续仍使用下一游标', async () => {
  read.mockResolvedValueOnce({ items: [], nextCursor: 'a' }).mockResolvedValueOnce({ items: [], nextCursor: 'b' })
    .mockResolvedValueOnce({ items: [], nextCursor: 'c' }).mockResolvedValueOnce({ items: [article()], nextCursor: null });
  const { result } = renderHook(() => useArticleSearch('社区', 'relevance', 'search-empty-pages'));
  await waitFor(() => expect(result.current.status).toBe('ready'));
  await act(() => result.current.loadMore());
  await act(() => result.current.loadMore());
  expect(result.current.paused).toBe(true);
  await act(() => result.current.loadMore());
  expect(read).toHaveBeenCalledTimes(3);
  await act(() => result.current.loadMore(true));
  expect(result.current.items).toHaveLength(1);
});

it('卸载取消续页，返回相同历史项恢复内容并能继续读取', async () => {
  const pending = deferred<FeedPage>();
  read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'next' }).mockReturnValueOnce(pending.promise)
    .mockResolvedValueOnce({ items: [article('2')], nextCursor: null });
  const first = renderHook(() => useArticleSearch('社区', 'latest', 'search-history'));
  await waitFor(() => expect(first.result.current.status).toBe('ready'));
  act(() => { void first.result.current.loadMore(); });
  first.unmount();
  expect(read.mock.calls[1][3]?.aborted).toBe(true);
  const returned = renderHook(() => useArticleSearch('社区', 'latest', 'search-history'));
  await waitFor(() => expect(returned.result.current.status).toBe('ready'));
  expect(returned.result.current.items[0].articleId).toBe('1');
  expect(read).toHaveBeenCalledTimes(2);
  await act(() => returned.result.current.loadMore());
  await act(async () => pending.resolve({ items: [article('stale')], nextCursor: null }));
  expect(returned.result.current.items.map((item) => item.articleId)).toEqual(['1', '2']);
});

it('重开首屏后离开覆盖旧历史快照，返回时不能恢复已经失效的游标', async () => {
  read.mockResolvedValueOnce({ items: [article('old')], nextCursor: 'old-cursor' })
    .mockImplementationOnce(() => new Promise(() => {}))
    .mockResolvedValueOnce({ items: [article('fresh')], nextCursor: null });
  const first = renderHook(() => useArticleSearch('社区', 'latest', 'search-cancel-restart'));
  await waitFor(() => expect(first.result.current.status).toBe('ready'));
  first.unmount();
  const returned = renderHook(() => useArticleSearch('社区', 'latest', 'search-cancel-restart'));
  await waitFor(() => expect(returned.result.current.items).toHaveLength(1));
  act(() => returned.result.current.restart());
  returned.unmount();
  const fresh = renderHook(() => useArticleSearch('社区', 'latest', 'search-cancel-restart'));
  await waitFor(() => expect(fresh.result.current.items[0]?.articleId).toBe('fresh'));
  expect(read).toHaveBeenLastCalledWith('社区', 'latest', null, expect.any(AbortSignal));
});

it('StrictMode 重放与页面卸载均取消在途请求，不遗留请求锁', () => {
  read.mockImplementation(() => new Promise(() => {}));
  const { unmount } = renderHook(() => useArticleSearch('社区', 'relevance', 'search-strict'), { reactStrictMode: true });
  expect(read).toHaveBeenCalledTimes(2);
  expect(read.mock.calls[0][3]?.aborted).toBe(true);
  unmount();
  expect(read.mock.calls[1][3]?.aborted).toBe(true);
});
