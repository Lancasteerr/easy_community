import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, checkDeletion, deleteArticle, getCsrf } from '../api/client';
import { article, deferred } from '../test/fixtures';
import { useArticleDeletion } from './useArticleDeletion';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(),
  getCsrf: vi.fn(), deleteArticle: vi.fn(), checkDeletion: vi.fn() }));
const csrf = vi.mocked(getCsrf);
const remove = vi.mocked(deleteArticle);
const check = vi.mocked(checkDeletion);
const expired = vi.fn();
const removed = vi.fn();
const token = { token: 'masked', headerName: 'X-CSRF-TOKEN' };
const mount = () => renderHook(() => useArticleDeletion(true, expired, removed));
beforeEach(() => {
  csrf.mockReset().mockResolvedValue(token);
  remove.mockReset().mockResolvedValue(undefined);
  check.mockReset(); expired.mockReset(); removed.mockReset();
});

describe('删除确认与核对', () => {
  it('取消不发送请求；等待令牌和删除期间锁定重复提交', async () => {
    const pending = deferred<void>();
    remove.mockReturnValue(pending.promise);
    const { result } = mount();
    act(() => { result.current.open(article()); });
    act(() => { result.current.close(); });
    expect(csrf).not.toHaveBeenCalled();
    act(() => { result.current.open(article()); void result.current.confirm(); void result.current.confirm(); result.current.close(); });
    expect(result.current.state.busy).toBe('delete');
    await waitFor(() => expect(remove).toHaveBeenCalledTimes(1));
    await act(async () => pending.resolve());
    expect(removed).toHaveBeenCalledWith(article().articleId, '删除成功');
    expect(result.current.state.article).toBeNull();
  });

  it('前置凭证失败不发送DELETE，也不进入结果未知', async () => {
    csrf.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络失败'));
    const { result } = mount();
    act(() => { result.current.open(article()); });
    await act(() => result.current.confirm());
    expect(remove).not.toHaveBeenCalled();
    expect(result.current.state.unknown).toBe(false);
    expect(result.current.state.message).toContain('获取操作凭证');
  });

  it('明确失败保留条目，CSRF刷新后只在再次确认时提交', async () => {
    remove.mockRejectedValueOnce(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '回滚'))
      .mockRejectedValueOnce(new ApiError(403, 'ACCESS_OR_CSRF_DENIED', 'CSRF')).mockResolvedValueOnce(undefined);
    csrf.mockResolvedValueOnce(token).mockResolvedValueOnce({ ...token, token: 'fresh' });
    const { result } = mount();
    act(() => { result.current.open(article()); });
    await act(() => result.current.confirm());
    expect(result.current.state.message).toBe('删除失败，请重试');
    expect(removed).not.toHaveBeenCalled();
    await act(() => result.current.confirm());
    expect(result.current.state.message).toContain('凭证已刷新');
    expect(remove).toHaveBeenCalledTimes(2);
    await act(() => result.current.confirm());
    expect(remove).toHaveBeenLastCalledWith(article().articleId, { ...token, token: 'fresh' }, expect.any(AbortSignal));
  });

  it('结果未知允许关闭后重新核对，隐藏确认才移除，不自动重发', async () => {
    remove.mockRejectedValueOnce(new ApiError(503, 'ARTICLE_WRITE_RESULT_UNKNOWN', '未知'));
    check.mockResolvedValue('hidden');
    const { result } = mount();
    act(() => { result.current.open(article()); });
    await act(() => result.current.confirm());
    expect(result.current.state.unknown).toBe(true);
    await act(() => result.current.confirm());
    act(() => { result.current.close(); result.current.open(article()); });
    expect(result.current.state.unknown).toBe(true);
    await act(() => result.current.check());
    expect(remove).toHaveBeenCalledTimes(1);
    expect(removed).toHaveBeenCalledWith(article().articleId, '内容已删除');
    expect(result.current.uncertainIds.size).toBe(0);
  });

  it('核对失败保留待确认，可读时保留条目并要求再次确认删除', async () => {
    remove.mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '超时'));
    check.mockRejectedValueOnce(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败')).mockResolvedValueOnce('visible');
    const { result } = mount();
    act(() => { result.current.open(article()); });
    await act(() => result.current.confirm());
    await act(() => result.current.check());
    expect(result.current.state.unknown).toBe(true);
    await act(() => result.current.check());
    expect(result.current.state.unknown).toBe(false);
    expect(result.current.state.canCheck).toBe(true);
    expect(result.current.state.message).toContain('仍可读取');
    expect(remove).toHaveBeenCalledTimes(1);
    expect(removed).not.toHaveBeenCalled();
  });

  it('429关闭重开仍受限，401上报过期，404按不可用移除', async () => {
    remove.mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', Date.now() + 30_000));
    const { result } = mount();
    act(() => { result.current.open(article()); });
    await act(() => result.current.confirm());
    act(() => { result.current.close(); result.current.open(article()); });
    await act(() => result.current.confirm());
    expect(remove).toHaveBeenCalledTimes(1);
    remove.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '过期'));
    const second = mount();
    act(() => { second.result.current.open(article()); });
    await act(() => second.result.current.confirm());
    expect(expired).toHaveBeenCalledTimes(1);
    remove.mockRejectedValueOnce(new ApiError(404, 'ARTICLE_NOT_FOUND', '不存在'));
    const third = mount();
    act(() => { third.result.current.open(article()); });
    await act(() => third.result.current.confirm());
    expect(removed).toHaveBeenCalledWith(article().articleId, '内容已不存在');
  });

  it('卸载取消等待，晚到写响应不触发旧页面回调', async () => {
    const pending = deferred<void>();
    remove.mockReturnValue(pending.promise);
    const { result, unmount } = mount();
    act(() => { result.current.open(article()); void result.current.confirm(); });
    await waitFor(() => expect(remove).toHaveBeenCalledTimes(1));
    unmount();
    expect(remove.mock.calls[0][2]?.aborted).toBe(true);
    await act(async () => pending.resolve());
    expect(removed).not.toHaveBeenCalled();
  });
});
