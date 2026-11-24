import { act, renderHook } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { ApiError, getCsrf } from '../../api/client';
import { getCommentSubmission, submitComment } from '../../api/comments';
import { useCommentDrafts } from './useCommentDrafts';

vi.mock('../../api/client', async (original) => ({
  ...await original<typeof import('../../api/client')>(), getCsrf: vi.fn(),
}));
vi.mock('../../api/comments', () => ({ getCommentSubmission: vi.fn(), submitComment: vi.fn() }));

it('提交结果未知时保留草稿，手动重试沿用原编号', async () => {
  vi.mocked(getCsrf).mockResolvedValue({ headerName: 'X-CSRF-TOKEN', token: 'masked' });
  const submit = vi.mocked(submitComment);
  submit.mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '超时'));
  submit.mockResolvedValueOnce({ state: 'PUBLISHED', commentCount: 1 });
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
  expect(onPublished).toHaveBeenCalledWith(null, id, 1);
  expect(result.current.getDraft(null).text).toBe('');
});
