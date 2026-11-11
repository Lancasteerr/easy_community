import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { article } from '../../test/fixtures';
import { ManageArticleRow, compactCount } from './ManageArticleRow';
import { DeleteArticleDialog, type DeleteDialogState } from './DeleteArticleDialog';
import { ManageEmpty, ManageError, ManageSkeleton } from './ManageStates';

// jsdom 不实现原生模态层；焦点约束、遮罩和真实布局交由浏览器验收。
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
});

describe('管理页展示边界', () => {
  it('空状态和加载失败使用原稿文案与单一操作入口', () => {
    const retry = vi.fn();
    const { rerender } = render(<ManageError onRetry={retry} />);
    expect(screen.getByRole('heading')).toHaveTextContent('内容加载失败');
    expect(screen.getByText('请检查网络连接后重试')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
    expect(retry).toHaveBeenCalledTimes(1);
    rerender(<ManageEmpty />);
    expect(screen.getByRole('heading')).toHaveTextContent('暂无已发布内容');
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.getByRole('button')).toHaveTextContent('去发布');
  });
  it('真实零值、未开放指标及坏图复用无封面占位，完整标题保留', () => {
    const data = { ...article(), coverUrl: '/broken.png', viewCount: 0 };
    const remove = vi.fn();
    render(<ManageArticleRow article={data} onDelete={remove} />);
    expect(screen.getByLabelText('阅读 0')).toHaveTextContent('0');
    expect(screen.getByLabelText('评论暂未开放')).toHaveTextContent('—');
    expect(screen.getByTitle(data.title)).toHaveTextContent(data.title);
    fireEvent.error(screen.getByRole('presentation'));
    expect(screen.getByText('暂无封面')).toBeVisible();
    expect(screen.queryByText('封面加载失败')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: `删除 ${data.title}` }));
    expect(remove).toHaveBeenCalledWith(data);
    expect(compactCount(128000)).toBe('12.8万');
    expect(compactCount(100000000)).toBe('1亿');
  });

  it('首次骨架提供明确的加载状态', () => {
    const { container } = render(<ManageSkeleton />);
    expect(screen.getByRole('status')).toHaveAccessibleName('正在加载已发布文章');
    expect(container.querySelectorAll('.manage-skeleton-row')).toHaveLength(5);
  });

  it('确认弹窗聚焦取消，处理中禁用关闭与重复提交', () => {
    const state: DeleteDialogState = { article: article(), busy: null, unknown: false, canCheck: false, message: '', retryAt: 0 };
    const close = vi.fn();
    const props = { state, onClose: close, onConfirm: vi.fn(), onCheck: vi.fn() };
    const { rerender } = render(<DeleteArticleDialog {...props} />);
    expect(screen.getByRole('button', { name: '取消' })).toHaveFocus();
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
    expect(close).toHaveBeenCalledTimes(1);
    rerender(<DeleteArticleDialog {...props} state={{ ...state, busy: 'delete' }} />);
    expect(screen.getByRole('button', { name: '删除中…' })).toBeDisabled();
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
    expect(close).toHaveBeenCalledTimes(1);
  });
});
