import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getCsrf } from '../api/client';
import { setArticleLike } from '../api/articles';
import type { ArticleLikeAccepted } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { article, deferred, profile } from '../test/fixtures';
import { ArticleCard } from './ArticleCard';
import { ToastProvider } from './Toast';

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../api/articles', () => ({ setArticleLike: vi.fn() }));
vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(), getCsrf: vi.fn() }));

const write = vi.mocked(setArticleLike);
const credentials = vi.mocked(getCsrf);
const login = vi.fn();
const expireSession = vi.fn();

/** 只替换登录态与接口，卡片、点赞 Hook、限流计时及反馈使用真实实现。 */
function renderCards() {
  return render(<MemoryRouter><ToastProvider>
    <ArticleCard article={article('1')} onLogin={login} />
    <ArticleCard article={{ ...article('2'), likedByMe: true, likeCount: 0 }} onLogin={login} />
  </ToastProvider></MemoryRouter>);
}

beforeEach(() => {
  vi.mocked(useAuth).mockReturnValue({ status: 'authenticated', user: profile, error: null, refreshing: false,
    refresh: vi.fn(), expireSession });
  credentials.mockReset().mockResolvedValue({ token: 'token', headerName: 'X-CSRF-TOKEN' });
  write.mockReset().mockImplementation(async (_id, liked, operationId) => ({ accepted: true, liked, operationId, version: '1' }));
  login.mockReset(); expireSession.mockReset();
});

it('卡片只在受理后高亮和更新数量，写入期间阻止重复点击，其他卡片仍可操作', async () => {
  const pending = deferred<ArticleLikeAccepted>();
  write.mockReturnValueOnce(pending.promise);
  renderCards();
  const first = screen.getByRole('article', { name: '文章 1' });
  const second = screen.getByRole('article', { name: '文章 2' });
  const like = within(first).getByRole('button', { name: '点赞 328' });
  fireEvent.click(like);
  fireEvent.click(like);
  await waitFor(() => expect(write).toHaveBeenCalledTimes(1));
  expect(like).toBeDisabled();
  expect(like).toHaveAttribute('aria-busy', 'true');
  expect(like).toHaveAttribute('aria-pressed', 'false');
  expect(like).toHaveTextContent('328');

  // 另一张已赞卡片可以独立取消，计数不会减为负数。
  fireEvent.click(within(second).getByRole('button', { name: '取消点赞 0' }));
  await waitFor(() => expect(write).toHaveBeenCalledTimes(2));
  expect(write.mock.calls[1].slice(0, 2)).toEqual(['2', false]);
  await waitFor(() => expect(within(second).getByRole('button', { name: '点赞 0' })).toBeEnabled());

  const operationId = write.mock.calls[0][2];
  await act(async () => pending.resolve({ accepted: true, liked: true, operationId, version: '1' }));
  expect(like).toHaveAccessibleName('取消点赞 329');
  expect(like).toHaveClass('is-liked');
  expect(like).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(like);
  await waitFor(() => expect(like).toHaveAccessibleName('点赞 328'));
  expect(like).not.toHaveClass('is-liked');
  expect(write.mock.calls[2].slice(0, 2)).toEqual(['1', false]);
  expect(write.mock.calls[2][2]).not.toBe(operationId);
});

it('游客点击只进入登录，不获取凭证或发送点赞', () => {
  vi.mocked(useAuth).mockReturnValue({ ...useAuth(), status: 'guest', user: null });
  renderCards();
  fireEvent.click(screen.getByRole('button', { name: '点赞 328' }));
  expect(login).toHaveBeenCalledTimes(1);
  expect(credentials).not.toHaveBeenCalled();
  expect(write).not.toHaveBeenCalled();
});

it.each([
  { status: 'loading' as const },
  { status: 'error' as const },
  { refreshing: true },
  { error: new Error('复核失败') },
])('登录态尚不可操作时禁用卡片按钮：%s', (state) => {
  vi.mocked(useAuth).mockReturnValue({ ...useAuth(), ...state });
  renderCards();
  const like = screen.getByRole('button', { name: '点赞 328' });
  expect(like).toBeDisabled();
  fireEvent.click(like);
  expect(write).not.toHaveBeenCalled();
  expect(login).not.toHaveBeenCalled();
});

it('401 清理会话并回登录，403 只刷新凭证且等待用户再次点击', async () => {
  write.mockRejectedValueOnce(new ApiError(403, 'ACCESS_OR_CSRF_DENIED', '凭证失效'));
  renderCards();
  const like = screen.getByRole('button', { name: '点赞 328' });
  fireEvent.click(like);
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('操作凭证已刷新，请再次点击'));
  expect(credentials).toHaveBeenCalledTimes(2);
  expect(write).toHaveBeenCalledTimes(1);
  expect(like).toBeEnabled();
  write.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '身份失效'));
  fireEvent.click(like);
  await waitFor(() => expect(login).toHaveBeenCalledTimes(1));
  expect(expireSession).toHaveBeenCalledTimes(1);
});

it('限流期间禁用并显示剩余时间，到期后恢复操作', async () => {
  // 固定 Date.now 并让倒计时读取期限边界，避免长时间等待或替换 testing-library 的计时器。
  const now = Date.now();
  const clock = vi.spyOn(Date, 'now').mockReturnValue(now);
  try {
    write.mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', '限流', now + 2000));
    const view = renderCards();
    const like = screen.getByRole('button', { name: '点赞 328' });
    fireEvent.click(like);
    await waitFor(() => expect(like).toHaveAttribute('title', '2秒后重试'));
    expect(like).toBeDisabled();
    fireEvent.click(like);
    expect(write).toHaveBeenCalledTimes(1);
    clock.mockReturnValue(now + 2001);
    await waitFor(() => expect(like).toBeEnabled());
    fireEvent.click(like);
    await waitFor(() => expect(like).toHaveAccessibleName('取消点赞 329'));
    view.unmount();
  } finally { clock.mockRestore(); }
});

it('文章删除时保留数量并提示，未知写入结果不更新也不自动重试', async () => {
  write.mockRejectedValueOnce(new ApiError(404, 'ARTICLE_NOT_FOUND', '已删除'));
  const view = renderCards();
  const like = screen.getByRole('button', { name: '点赞 328' });
  fireEvent.click(like);
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('文章不存在或已删除'));
  expect(like).toHaveAttribute('aria-pressed', 'false');
  view.unmount();

  write.mockRejectedValueOnce(new ApiError(503, 'LIKE_WRITE_RESULT_UNKNOWN', '结果未知'));
  renderCards();
  const uncertain = screen.getByRole('button', { name: '点赞 328' });
  fireEvent.click(uncertain);
  await waitFor(() => expect(write).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(uncertain).toBeEnabled());
  expect(uncertain).toHaveTextContent('328');
  expect(screen.getByRole('status')).toBeEmptyDOMElement();
});
