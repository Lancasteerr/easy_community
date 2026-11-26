import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getCsrf } from '../../api/client';
import { submitComment } from '../../api/comments';
import { useCommentDrafts } from './useCommentDrafts';

vi.mock('../../api/client', async (original) => ({
  ...await original<typeof import('../../api/client')>(), getCsrf: vi.fn(),
}));
vi.mock('../../api/comments', () => ({ submitComment: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getCsrf).mockResolvedValue({ headerName: 'X-CSRF-TOKEN', token: 'masked' });
});

it('提交结果未知时保留草稿，重试沿用原编号和正文', async () => {
  const submit = vi.mocked(submitComment);
  const published = { state: 'PUBLISHED' as const, comment: {
    id: '10', rootId: null, author: { id: '7', username: '作者' },
    content: '评论正文', createdAt: '2026-01-01T00:00:00Z',
  } };
  submit.mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '超时')).mockResolvedValueOnce(published);
  const onPublished = vi.fn();
  const { result } = renderHook(() => useCommentDrafts('1', true, '7', onPublished));

  act(() => result.current.edit(null, '评论正文'));
  await act(async () => { await result.current.publish(null); });
  const id = submit.mock.calls[0][1];
  expect(result.current.getDraft(null)).toMatchObject({ state: 'uncertain', text: '评论正文', submissionId: id });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(onPublished).not.toHaveBeenCalled();

  act(() => result.current.edit(null, '不能更换载荷'));
  await act(async () => { await result.current.publish(null); });
  expect(submit).toHaveBeenCalledTimes(2);
  expect(submit.mock.calls[1].slice(0, 4)).toEqual(['1', id, '评论正文', null]);
  expect(onPublished).toHaveBeenCalledOnce();
  expect(onPublished).toHaveBeenCalledWith(published);
  expect(result.current.getDraft(null).text).toBe('');
});

it('评论已不可见且重试只收到成功状态时仍清空草稿', async () => {
  const published = { state: 'PUBLISHED' as const };
  vi.mocked(submitComment).mockResolvedValueOnce(published);
  const onPublished = vi.fn();
  const { result } = renderHook(() => useCommentDrafts('1', true, '7', onPublished));

  act(() => result.current.edit(null, '评论正文'));
  await act(async () => { await result.current.publish(null); });

  expect(onPublished).toHaveBeenCalledWith(published);
  expect(submitComment).toHaveBeenCalledOnce();
  expect(result.current.getDraft(null).text).toBe('');
});

it('同步发布回复用目标评论 ID 提交并直接返回评论 DTO', async () => {
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
  await act(async () => { await result.current.publish(target); });

  expect(submitComment).toHaveBeenCalledWith('1', expect.any(String), '回复正文', '11',
    expect.anything(), expect.any(AbortSignal));
  expect(onPublished).toHaveBeenCalledWith(published);
  expect(result.current.target).toBeNull();
});
