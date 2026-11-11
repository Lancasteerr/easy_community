import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getMyArticles } from '../api/client';
import type { FeedPage } from '../api/types';
import { article, deferred } from '../test/fixtures';
import { useMyArticles } from './useMyArticles';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getMyArticles: vi.fn() }));
const read = vi.mocked(getMyArticles);
const expire = vi.fn();
const mount = () => renderHook(() => useMyArticles('author', true, expire));
beforeEach(() => { read.mockReset(); expire.mockReset(); });

describe('本人游标会话', () => {
  it('按ID去重，短页继续；同步触底只发送一个请求', async () => {
    const pending = deferred<FeedPage>();
    read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'next' }).mockReturnValueOnce(pending.promise);
    const { result } = mount();
    await waitFor(() => expect(result.current.status).toBe('ready'));
    act(() => { void result.current.loadMore(); void result.current.loadMore(); });
    expect(read).toHaveBeenCalledTimes(2);
    await act(async () => pending.resolve({ items: [article('1'), article('2')], nextCursor: null }));
    expect(result.current.items.map((item) => item.articleId)).toEqual(['1', '2']);
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('空页仍使用新游标，三页无新增暂停，手动继续可恢复', async () => {
    read.mockResolvedValueOnce({ items: [], nextCursor: 'a' }).mockResolvedValueOnce({ items: [], nextCursor: 'b' })
      .mockResolvedValueOnce({ items: [], nextCursor: 'c' }).mockResolvedValueOnce({ items: [article()], nextCursor: null });
    const { result } = mount();
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    await act(() => result.current.loadMore());
    expect(result.current.paused).toBe(true);
    await act(() => result.current.loadMore());
    expect(read).toHaveBeenCalledTimes(3);
    await act(() => result.current.loadMore(true));
    expect(result.current.items).toHaveLength(1);
  });

  it('刷新保留旧条目，失败禁用旧续页，刷新成功再替换并递增位置版本', async () => {
    const pending = deferred<FeedPage>();
    read.mockResolvedValueOnce({ items: [article('old')], nextCursor: 'old-cursor' }).mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ items: [article('new')], nextCursor: null });
    const { result } = mount();
    await waitFor(() => expect(result.current.status).toBe('ready'));
    act(() => { void result.current.refresh(); });
    expect(result.current.items[0].articleId).toBe('old');
    await act(async () => pending.reject(new ApiError(503, 'ARTICLE_SEARCH_UNAVAILABLE', '失败')));
    expect(result.current.status).toBe('refreshError');
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenCalledTimes(2);
    await act(() => result.current.refresh());
    expect(result.current.items[0].articleId).toBe('new');
    expect(result.current.revision).toBe(1);
    expect(read).toHaveBeenLastCalledWith(null, expect.any(AbortSignal));
  });

  it('续页失败重试相同游标；无效及过期游标要求重开查询', async () => {
    read.mockResolvedValueOnce({ items: [article()], nextCursor: 'same' }).mockRejectedValueOnce(new ApiError(503, 'ARTICLE_SEARCH_UNAVAILABLE', '失败'))
      .mockRejectedValueOnce(new ApiError(409, 'ARTICLE_CURSOR_EXPIRED', '过期')).mockResolvedValueOnce({ items: [], nextCursor: null });
    const { result } = mount();
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    expect(result.current.status).toBe('appendError');
    await act(() => result.current.retry());
    expect(read).toHaveBeenLastCalledWith('same', expect.any(AbortSignal));
    expect(result.current.status).toBe('resetRequired');
    await act(() => result.current.loadMore(true));
    expect(read).toHaveBeenCalledTimes(3);
    await act(() => result.current.refresh());
    expect(result.current.items).toEqual([]);
  });

  it('删除与在途续页合并，刷新也不会恢复已确认删除的旧卡片', async () => {
    const pending = deferred<FeedPage>();
    read.mockResolvedValueOnce({ items: [article('1')], nextCursor: 'next' }).mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ items: [article('1'), article('2')], nextCursor: null });
    const { result } = mount();
    await waitFor(() => expect(result.current.status).toBe('ready'));
    act(() => { void result.current.loadMore(); result.current.remove('1'); });
    await act(async () => pending.resolve({ items: [article('1'), article('2')], nextCursor: null }));
    expect(result.current.items.map((item) => item.articleId)).toEqual(['2']);
    await act(() => result.current.refresh());
    expect(result.current.items.map((item) => item.articleId)).toEqual(['2']);
  });

  it('刷新取消旧读取；忽略取消的迟到响应也不能覆盖新列表', async () => {
    const stale = deferred<FeedPage>();
    read.mockReturnValueOnce(stale.promise).mockResolvedValueOnce({ items: [article('fresh')], nextCursor: null });
    const { result } = mount();
    const signal = read.mock.calls[0][1]!;
    await act(() => result.current.refresh());
    expect(signal.aborted).toBe(true);
    await act(async () => stale.resolve({ items: [article('stale')], nextCursor: 'bad' }));
    expect(result.current.items[0].articleId).toBe('fresh');
  });

  it('身份复核只暂停新读取，同账号恢复不重读，换账号清理会话', async () => {
    read.mockResolvedValue({ items: [article()], nextCursor: null });
    const { result, rerender } = renderHook(({ id, enabled }) => useMyArticles(id, enabled, expire), { initialProps: { id: 'a', enabled: true } });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    rerender({ id: 'a', enabled: false });
    await act(() => result.current.refresh());
    expect(read).toHaveBeenCalledTimes(1);
    rerender({ id: 'a', enabled: true });
    expect(read).toHaveBeenCalledTimes(1);
    read.mockResolvedValue({ items: [article('other')], nextCursor: null });
    rerender({ id: 'b', enabled: true });
    await waitFor(() => expect(result.current.items[0]?.articleId).toBe('other'));
  });

  it('相同游标停止续页，401上报过期，429到期前禁止重试', async () => {
    read.mockResolvedValueOnce({ items: [article()], nextCursor: 'a' }).mockResolvedValueOnce({ items: [], nextCursor: 'a' })
      .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', Date.now() + 30_000));
    const { result } = mount();
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(() => result.current.loadMore());
    expect(result.current.status).toBe('resetRequired');
    await act(() => result.current.refresh());
    await act(() => result.current.refresh());
    expect(read).toHaveBeenCalledTimes(3);
    read.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '过期'));
    const other = mount();
    await waitFor(() => expect(expire).toHaveBeenCalledTimes(1));
    other.unmount();
  });

  it('严格模式重放可重建请求，卸载取消在途读取', () => {
    read.mockImplementation(() => new Promise(() => {}));
    const { unmount } = renderHook(() => useMyArticles('a', true, expire), { reactStrictMode: true });
    expect(read).toHaveBeenCalledTimes(2);
    expect(read.mock.calls[0][1]?.aborted).toBe(true);
    unmount();
    expect(read.mock.calls[1][1]?.aborted).toBe(true);
  });
});
