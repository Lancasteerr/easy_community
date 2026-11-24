import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { CommentComposer } from './CommentComposer';
import { validCommentText } from './useCommentDrafts';

const actions = { enabled: true, onChange: vi.fn(), onPublish: vi.fn(), viewerProfile: null };
const target = { id: '2', rootId: '1', username: '云间' };

it('回复只计正文码点，300 个字符可发布，超限保留正文', () => {
  const composer = (text: string) => <CommentComposer {...actions} target={target} draft={{ text, state: 'editing' }} />;
  const { rerender } = render(composer(''));
  const input = screen.getByRole('textbox', { name: '回复 @云间' });
  const publish = screen.getByRole('button', { name: '发布' });
  expect(input).toHaveAccessibleDescription('0/300');
  expect(publish).toBeDisabled();

  const limit = `  ${'😀'.repeat(299)}字\n`;
  rerender(composer(limit));
  expect(input).toHaveAccessibleDescription('300/300');
  expect(validCommentText(limit)).toBe(true);
  expect(publish).toBeEnabled();

  const excess = limit.trim() + '字';
  rerender(composer(excess));
  expect(input).toHaveAccessibleDescription('301/300');
  expect(screen.getByText('301/300')).toHaveAttribute('data-over-limit', 'true');
  expect(validCommentText(excess)).toBe(false);
  expect(publish).toBeDisabled();
  expect(input).toHaveValue(excess);
});

it('随草稿测量输入框高度，清空后恢复初始高度', () => {
  const composer = (text: string) => <CommentComposer {...actions} draft={{ text, state: 'editing' }} />;
  const { rerender } = render(composer(''));
  const input = screen.getByRole<HTMLTextAreaElement>('textbox');
  // 模拟排版后的滚动高度，只验证组件能否随草稿增长和回缩。
  Object.defineProperty(input, 'scrollHeight', { get: () => input.value.includes('\n') ? 128 : 64 });

  rerender(composer('第一行\n第二行\n第三行\n第四行'));
  expect(input).toHaveStyle({ height: '128px' });
  rerender(composer(''));
  expect(input).toHaveStyle({ height: '64px' });
});

it('已登录账号没有可用头像时显示用户名首字符', () => {
  const composer = (avatarUrl: string | null) => <CommentComposer {...actions}
    viewerProfile={{ username: ' 云间 ', avatarUrl }} draft={{ text: '', state: 'editing' }} />;
  const { container, rerender } = render(composer(null));
  expect(screen.getByText('云')).toBeVisible();
  expect(container.querySelector('img')).toBeNull();

  rerender(composer('/account-avatar.png'));
  const image = container.querySelector('img')!;
  expect(image).toHaveAttribute('src', '/account-avatar.png');
  fireEvent.error(image);
  expect(screen.getByText('云')).toBeVisible();
  expect(container.querySelector('img')).toBeNull();
});
