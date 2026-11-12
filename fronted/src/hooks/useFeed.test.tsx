import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getFeed } from '../api/client';
import type { FeedPage } from '../api/types';
import { article, deferred } from '../test/fixtures';
import { useFeed } from './useFeed';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getFeed: vi.fn() }));
const read = vi.mocked(getFeed);
beforeEach(() => { read.mockReset(); });
afterEach(() => vi.restoreAllMocks());

describe('推荐流请求与游标', () => {
  it('返回同一历史项恢复条目和游标，不重读首屏', async () => {
    read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'next' })
      .mockResolvedValueOnce({ items: [article('2')], nextCursor: null });
    const first = renderHook(() => useFeed('feed-history-test'));
    await waitFor(() => expect(first.result.current.status).toBe('ready'));
    first.unmount();
    const returned = renderHook(() => useFeed('feed-history-test'));
    await waitFor(() => expect(returned.result.current.items).toHaveLength(1));
    expect(read).toHaveBeenCalledTimes(1);
    await act(() => returned.result.current.loadMore());
    expect(read).toHaveBeenLastCalledWith('next', expect.any(AbortSignal));
    expect(returned.result.current.items.map((item) => item.articleId)).toEqual(['1', '2']);
  });
  it('短页仍续页，保持顺序并去除跨页及页内重复 ID', async () => {
    read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'opaque+/cursor=' })
      .mockResolvedValueOnce({ items: [article('1'), article('2'), article('2')], nextCursor: null });
    const { result } = renderHook(useFeed);
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    expect(read).toHaveBeenLastCalledWith('opaque+/cursor=', expect.any(AbortSignal));
    expect(result.current.items.map((item) => item.articleId)).toEqual(['1', '2']);
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('多个同步触底事件只发出一个请求', async () => {
    const pending = deferred<FeedPage>();
    read.mockResolvedValueOnce({ items: [article()], nextCursor: 'next' }).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(useFeed);
    await waitFor(() => expect(result.current.status).toBe('ready'));
    act(() => { void result.current.loadMore(); void result.current.loadMore(); void result.current.loadMore(true); });
    expect(read).toHaveBeenCalledTimes(2);
    await act(async () => pending.resolve({ items: [], nextCursor: null }));
  });

  it('连续空页继续使用新游标，三次后暂停自动加载，仍可手动继续', async () => {
    read.mockResolvedValueOnce({ items: [], nextCursor: 'a' }).mockResolvedValueOnce({ items: [], nextCursor: 'b' })
      .mockResolvedValueOnce({ items: [], nextCursor: 'c' }).mockResolvedValueOnce({ items: [article()], nextCursor: null });
    const { result } = renderHook(useFeed);
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    await act(() => result.current.loadMore());
    expect(result.current.paused).toBe(true);
    await act(() => result.current.loadMore());
    expect(read).toHaveBeenCalledTimes(3);
    await act(() => result.current.loadMore(true));
    expect(result.current.items).toHaveLength(1);
  });

  it('续页失败保留原列表，手动重试使用相同游标', async () => {
    read.mockResolvedValueOnce({ items: [article()], nextCursor: 'same' })
      .mockRejectedValueOnce(new ApiError(503, 'ARTICLE_SEARCH_UNAVAILABLE', '失败'))
      .mockResolvedValueOnce({ items: [article('2')], nextCursor: null });
    const { result } = renderHook(useFeed);
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    expect(result.current.status).toBe('error');
    expect(result.current.items).toHaveLength(1);
    await act(() => result.current.loadMore());
    expect(read).toHaveBeenCalledTimes(2);
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenLastCalledWith('same', expect.any(AbortSignal));
    expect(result.current.items).toHaveLength(2);
  });

  it('过期后保留已读内容，只有重新加载才从首屏开始', async () => {
    read.mockResolvedValueOnce({ items: [article('old')], nextCursor: 'expired' })
      .mockRejectedValueOnce(new ApiError(409, 'ARTICLE_CURSOR_EXPIRED', '过期'))
      .mockResolvedValueOnce({ items: [article('fresh')], nextCursor: null });
    const { result } = renderHook(useFeed);
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    expect(result.current.status).toBe('expired');
    expect(result.current.items[0].articleId).toBe('old');
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenCalledTimes(2);
    act(() => result.current.restart());
    await waitFor(() => expect(result.current.items[0]?.articleId).toBe('fresh'));
    expect(read).toHaveBeenLastCalledWith(null, expect.any(AbortSignal));
  });

  it('429 限流到期前不能重试', async () => {
    const now = Date.now();
    read.mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', now + 30_000))
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    const { result } = renderHook(useFeed);
    await waitFor(() => expect(result.current.status).toBe('error'));
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenCalledTimes(1);
    vi.spyOn(Date, 'now').mockReturnValue(now + 30_001);
    await act(() => result.current.loadMore(true));
    expect(result.current.status).toBe('ready');
  });

  it('即使读取方忽略取消，重载前的迟到响应也不会覆盖新列表', async () => {
    const stale = deferred<FeedPage>();
    read.mockReturnValueOnce(stale.promise).mockResolvedValueOnce({ items: [article('fresh')], nextCursor: null });
    const { result } = renderHook(useFeed);
    const oldSignal = read.mock.calls[0][1]!;
    act(() => result.current.restart());
    await waitFor(() => expect(result.current.items[0]?.articleId).toBe('fresh'));
    expect(oldSignal.aborted).toBe(true);
    await act(async () => stale.resolve({ items: [article('stale')], nextCursor: 'old' }));
    expect(result.current.items[0].articleId).toBe('fresh');
  });

  it('严格模式重放可以正常加载，卸载取消正在执行的请求', async () => {
    read.mockImplementation(() => new Promise(() => {}));
    const { unmount } = renderHook(useFeed, { reactStrictMode: true });
    expect(read).toHaveBeenCalledTimes(2);
    expect(read.mock.calls[0][1]?.aborted).toBe(true);
    unmount();
    expect(read.mock.calls[1][1]?.aborted).toBe(true);
  });
});
