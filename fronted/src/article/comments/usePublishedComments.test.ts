import { act, renderHook } from '@testing-library/react';
import { expect, it } from 'vitest';
import type { CommentItem } from '../../api/comments';
import { mergePublishedComments, usePublishedComments, visibleReplies } from './usePublishedComments';

function comment(id: string, rootId: string | null = null): CommentItem {
  return { id, rootId, author: { id: '7', username: '作者' }, content: id, createdAt: '2026-01-01T00:00:00Z' };
}

it('正式列表接管同 ID 的数据和位置，收起时新旧回复合计两条', () => {
  const newest = comment('4', '1');
  const temporary = comment('3', '1');
  const official = { ...temporary, content: '正式正文' };
  const older = comment('2', '1');
  const merged = mergePublishedComments([newest, temporary], [older, official]);

  expect(merged).toEqual([newest, older, official]);
  expect(visibleReplies(merged, false)).toEqual([newest, older]);
  expect(visibleReplies(merged, true)).toEqual(merged);
});

it('临时评论按楼层隔离，重复确认保留一条，正式接管时只移除同 ID 条目', () => {
  const { result } = renderHook(() => usePublishedComments('1', '7'));
  const root = comment('10');
  const first = comment('11', '10');
  const second = comment('12', '10');
  act(() => {
    result.current.remember(root);
    result.current.remember(first);
    result.current.remember(first);
    result.current.remember(second);
  });
  expect(result.current.byRoot.get(null)).toEqual([root]);
  expect(result.current.byRoot.get('10')).toEqual([second, first]);

  act(() => result.current.reconcile('10', [second]));
  expect(result.current.byRoot.get('10')).toEqual([first]);
  expect(result.current.byRoot.get(null)).toEqual([root]);
});

it('切换文章或账号时清理临时评论，并忽略旧作用域的确认', () => {
  const { result, rerender } = renderHook(({ articleId, userId }) => usePublishedComments(articleId, userId),
    { initialProps: { articleId: '1', userId: '7' } });
  const oldRemember = result.current.remember;
  act(() => oldRemember(comment('10')));

  rerender({ articleId: '2', userId: '7' });
  act(() => oldRemember(comment('11')));
  expect(result.current.byRoot.size).toBe(0);

  act(() => result.current.remember(comment('12')));
  rerender({ articleId: '2', userId: '8' });
  expect(result.current.byRoot.size).toBe(0);
});
