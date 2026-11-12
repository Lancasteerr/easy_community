import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { ApiError, getCsrf, getProfile, postLogin } from '../api/client';
import { getArticleDetail, setArticleLike } from '../api/articles';
import { article, profile } from '../test/fixtures';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(),
  getProfile: vi.fn(), getCsrf: vi.fn(), postLogin: vi.fn() }));
vi.mock('../api/articles', () => ({ getArticleDetail: vi.fn(), setArticleLike: vi.fn() }));

function LocationProbe() {
  const location = useLocation();
  return <output aria-label="当前位置">{location.pathname}</output>;
}

beforeEach(() => {
  vi.mocked(getProfile).mockReset().mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客'));
  vi.mocked(getCsrf).mockReset().mockResolvedValue({ token: 'masked', headerName: 'X-CSRF-TOKEN' });
  vi.mocked(postLogin).mockReset().mockResolvedValue(profile);
  vi.mocked(getArticleDetail).mockReset().mockResolvedValue({ ...article('9007199254740995'), coverMediaId: null, contentHtml: '<p>真实正文</p>' });
  vi.mocked(setArticleLike).mockReset();
});

it('游客可以阅读，无标题不渲染目录，评论按钮只提示未开放', async () => {
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /></MemoryRouter>);
  await screen.findByText('真实正文');
  expect(screen.queryByRole('navigation', { name: '文章目录' })).toBeNull();
  expect(screen.getByText('作者简介暂未开放')).toBeVisible();
  await user.click(screen.getByRole('button', { name: '评论暂未开放' }));
  expect(screen.getByRole('status')).toHaveTextContent('评论暂未开放');
  expect(getArticleDetail).toHaveBeenCalledTimes(1);
});

it('游客点赞登录后回原文章重新读取状态，不自动补发点赞', async () => {
  vi.mocked(getProfile).mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客')).mockResolvedValue(profile);
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /><LocationProbe /></MemoryRouter>);
  const like = await screen.findByRole('button', { name: /^点赞 / });
  await waitFor(() => expect(like).toBeEnabled());
  await user.click(like);
  await screen.findByRole('heading', { name: '账号密码登录' });
  await user.type(screen.getByLabelText('账号'), 'Alice_1');
  await user.type(screen.getByLabelText('密码', { exact: true }), 'password');
  await user.click(screen.getByRole('button', { name: /^登录$/ }));
  await screen.findByText('真实正文');
  expect(screen.getByLabelText('当前位置')).toHaveTextContent('/articles/9007199254740995');
  expect(setArticleLike).not.toHaveBeenCalled();
  expect(getArticleDetail).toHaveBeenCalledTimes(2);
});
