import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { useState } from 'react';
import { searchTags } from '../api/articles';
import { TagSelector } from './TagSelector';
import type { Tag } from './model';
import { deferred } from '../test/fixtures';

vi.mock('../api/articles', () => ({ searchTags: vi.fn() }));
function Selector({ tags = [] }: { tags?: Tag[] }) { const [value, setValue] = useState(tags); return <TagSelector value={value} disabled={false} onChange={setValue} onUnauthorized={vi.fn()} />; }
it('空输入不加载目录，迟到响应不能覆盖当前查询', async () => {
  const old = deferred<Tag[]>();
  vi.mocked(searchTags).mockReset().mockReturnValueOnce(old.promise).mockResolvedValue([{ tagId: '2', name: '新结果' }]);
  render(<Selector />);
  const input = screen.getByRole('combobox'); fireEvent.focus(input);
  expect(searchTags).not.toHaveBeenCalled();
  fireEvent.change(input, { target: { value: '旧' } }); await waitFor(() => expect(searchTags).toHaveBeenCalledTimes(1));
  fireEvent.change(input, { target: { value: '新' } }); await screen.findByRole('option', { name: '新结果' });
  await act(async () => old.resolve([{ tagId: '1', name: '旧结果' }])); expect(screen.queryByText('旧结果')).toBeNull();
  fireEvent.keyDown(input, { key: 'Enter' }); expect(screen.getByText('已选 1/5 · 最多选择 5 个标签')).toBeVisible();
  expect(screen.getByRole('option', { name: '新结果 · 已选择' })).toBeDisabled();
});
it('五项后禁用搜索，移除恢复；搜索失败不清理已有标签', async () => {
  vi.mocked(searchTags).mockReset().mockRejectedValue(new Error('failed'));
  render(<Selector tags={Array.from({ length: 5 }, (_, index) => ({ tagId: String(index), name: `标签${index}` }))} />);
  const input = screen.getByRole('combobox'); expect(input).toBeDisabled();
  fireEvent.click(screen.getByLabelText('移除标签 标签0')); expect(input).not.toBeDisabled();
  fireEvent.focus(input); fireEvent.change(input, { target: { value: '错误' } });
  await screen.findByText('标签搜索失败'); expect(screen.getByText('已选 4/5 · 最多选择 5 个标签')).toBeVisible();
});
