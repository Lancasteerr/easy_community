import { act, renderHook, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { getArticleEdit } from '../api/articles';
import { ApiError } from '../api/client';
import { useArticleDocument } from './useArticleDocument';
import { emptyForm, type ArticleEditData } from './model';

vi.mock('../api/articles', () => ({ getArticleEdit: vi.fn() }));
it('新建转编辑不重读、不丢内容；编辑后恢复基线清除脏标记', () => {
  const { result, rerender } = renderHook(({ id }: { id?: string }) => useArticleDocument(id, vi.fn()), { initialProps: {} as { id?: string } });
  const form = { ...emptyForm(), title: '保存标题' };
  act(() => result.current.saved('12', form)); rerender({ id: '12' });
  expect(getArticleEdit).not.toHaveBeenCalled(); expect(result.current.form.title).toBe('保存标题'); expect(result.current.dirty).toBe(false);
  act(() => result.current.setForm({ ...form, title: '新标题' })); expect(result.current.dirty).toBe(true);
  act(() => result.current.setForm(form)); expect(result.current.dirty).toBe(false);
});
it.each([[404, 'ARTICLE_NOT_FOUND', 'missing'], [403, 'ARTICLE_FORBIDDEN', 'forbidden'], [409, 'ARTICLE_HIDDEN', 'hidden'], [503, 'DEPENDENCY_UNAVAILABLE', 'error']])('作者接口错误 %s %s 映射页面状态', async (status, code, expected) => {
  vi.mocked(getArticleEdit).mockReset().mockRejectedValue(new ApiError(status as number, code as string, 'failed'));
  const { result } = renderHook(() => useArticleDocument('12', vi.fn())); await waitFor(() => expect(result.current.state).toBe(expected));
});
it('切换文章后的迟到响应不覆盖当前文章', async () => {
  let resolve!: (value: ArticleEditData) => void;
  vi.mocked(getArticleEdit).mockReset().mockReturnValueOnce(new Promise((done) => { resolve = done; })).mockResolvedValue({ ...emptyForm(), articleId: '2', title: '第二篇' } as ArticleEditData);
  const { result, rerender } = renderHook(({ id }) => useArticleDocument(id, vi.fn()), { initialProps: { id: '1' } });
  rerender({ id: '2' }); await waitFor(() => expect(result.current.form.title).toBe('第二篇'));
  await act(async () => resolve({ ...emptyForm(), articleId: '1', title: '第一篇' } as ArticleEditData)); expect(result.current.form.title).toBe('第二篇');
});
