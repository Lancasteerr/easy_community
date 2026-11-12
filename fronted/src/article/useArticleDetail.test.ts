import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { getArticleDetail } from '../api/articles';
import type { ArticleDetailData } from '../api/types';
import { article, deferred } from '../test/fixtures';
import { useArticleDetail } from './useArticleDetail';

vi.mock('../api/articles', () => ({ getArticleDetail: vi.fn() }));
const read = vi.mocked(getArticleDetail);
const data = (id: string): ArticleDetailData => ({ ...article(id), coverMediaId: null, contentHtml: '<p>正文</p>' });
beforeEach(() => read.mockReset());

it('切换文章后隔离忽略取消的旧响应，并保留字符串大整数', async () => {
  const stale = deferred<ArticleDetailData>();
  read.mockReturnValueOnce(stale.promise).mockResolvedValueOnce(data('9007199254740995'));
  const { result, rerender } = renderHook(({ id }) => useArticleDetail(id), { initialProps: { id: '1' } });
  const signal = read.mock.calls[0][1]!;
  rerender({ id: '9007199254740995' });
  await waitFor(() => expect(result.current.article?.articleId).toBe('9007199254740995'));
  expect(signal.aborted).toBe(true);
  await act(async () => stale.resolve(data('1')));
  expect(result.current.article?.articleId).toBe('9007199254740995');
});

it('越界标识直接显示不存在，不发出公开请求', async () => {
  const { result } = renderHook(() => useArticleDetail('9223372036854775808'));
  await waitFor(() => expect(result.current.status).toBe('missing'));
  expect(read).not.toHaveBeenCalled();
});
