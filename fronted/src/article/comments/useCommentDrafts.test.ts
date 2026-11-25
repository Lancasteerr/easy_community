import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getCsrf } from '../../api/client';
import { getCommentSubmission, submitComment } from '../../api/comments';
import { useCommentDrafts } from './useCommentDrafts';

vi.mock('../../api/client', async (original) => ({
  ...await original<typeof import('../../api/client')>(), getCsrf: vi.fn(),
}));
vi.mock('../../api/comments', () => ({ getCommentSubmission: vi.fn(), submitComment: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getCsrf).mockResolvedValue({ headerName: 'X-CSRF-TOKEN', token: 'masked' });
});

it('提交结果未知时保留草稿，手动重试沿用原编号并返回评论 DTO', async () => {
  const submit = vi.mocked(submitComment);
  submit.mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '超时'));
  const published = { state: 'PUBLISHED' as const, commentCount: 1, comment: {
    id: '10', rootId: null, author: { id: '7', username: '作者' },
    content: '评论正文', createdAt: '2026-01-01T00:00:00Z',
  } };
  submit.mockResolvedValueOnce(published);
  const onPublished = vi.fn();
  const { result } = renderHook(() => useCommentDrafts('1', true, '7', onPublished));

  act(() => result.current.edit(null, '评论正文'));
  await act(async () => { await result.current.publish(null, 'submit'); });
  const id = submit.mock.calls[0][1];
  expect(result.current.getDraft(null)).toMatchObject({ state: 'uncertain', text: '评论正文', submissionId: id });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(getCommentSubmission).not.toHaveBeenCalled();

  await act(async () => { await result.current.publish(null, 'submit'); });
  expect(submit.mock.calls[1].slice(0, 4)).toEqual(['1', id, '评论正文', null]);
  expect(onPublished).toHaveBeenCalledWith(null, published);
  expect(result.current.getDraft(null).text).toBe('');
});

it('状态查询确认成功且未返回 DTO 时仍清空草稿', async () => {
  vi.mocked(submitComment).mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '超时'));
  const published = { state: 'PUBLISHED' as const, commentCount: 1 };
  vi.mocked(getCommentSubmission).mockResolvedValueOnce(published);
  const onPublished = vi.fn();
  const { result } = renderHook(() => useCommentDrafts('1', true, '7', onPublished));

  act(() => result.current.edit(null, '待确认正文'));
  await act(async () => { await result.current.publish(null, 'submit'); });
  const id = result.current.getDraft(null).submissionId;
  await act(async () => { await result.current.publish(null, 'check'); });

  expect(getCommentSubmission).toHaveBeenCalledWith('1', id, expect.any(AbortSignal));
  expect(submitComment).toHaveBeenCalledTimes(1);
  expect(onPublished).toHaveBeenCalledWith(null, published);
  expect(result.current.getDraft(null).text).toBe('');
});

it('一级评论异步确认后才交付评论 DTO', async () => {
  vi.useFakeTimers();
  try {
    const published = { state: 'PUBLISHED' as const, commentCount: 1, comment: {
      id: '10', rootId: null, author: { id: '7', username: '作者' },
      content: '异步评论', createdAt: '2026-01-01T00:00:00Z',
    } };
    vi.mocked(submitComment).mockResolvedValueOnce({ state: 'PUBLISHING' });
    vi.mocked(getCommentSubmission).mockResolvedValueOnce(published);
    const onPublished = vi.fn();
    const { result } = renderHook(() => useCommentDrafts('1', true, '7', onPublished));

    act(() => result.current.edit(null, '异步评论'));
    let publishing = Promise.resolve();
    await act(async () => {
      publishing = result.current.publish(null, 'submit');
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(onPublished).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(650);
      await publishing;
    });
    const id = vi.mocked(submitComment).mock.calls[0][1];
    expect(getCommentSubmission).toHaveBeenCalledWith('1', id, expect.any(AbortSignal));
    expect(onPublished).toHaveBeenCalledWith(null, published);
  } finally {
    vi.useRealTimers();
  }
});

it('同步发布回复用目标评论 ID 提交并交付带账号名的 DTO', async () => {
  const target = { id: '11', rootId: '10', username: '回复对象' };
  const published = { state: 'PUBLISHED' as const, comment: {
    id: '12', rootId: '10', replyToUsername: '回复对象', author: { id: '7', username: '作者' },
    content: '回复正文', createdAt: '2026-01-01T00:00:00Z',
  } };
  vi.mocked(submitComment).mockResolvedValueOnce(published);
  const onPublished = vi.fn();
  const { result } = renderHook(() => useCommentDrafts('1', true, '7', onPublished));

  act(() => {
    result.current.setTarget(target);
    result.current.edit(target, '回复正文');
  });
  await act(async () => { await result.current.publish(target, 'submit'); });

  expect(submitComment).toHaveBeenCalledWith('1', expect.any(String), '回复正文', '11',
    expect.anything(), expect.any(AbortSignal));
  expect(getCommentSubmission).not.toHaveBeenCalled();
  expect(onPublished).toHaveBeenCalledWith(target, published);
  expect(result.current.target).toBeNull();
});
