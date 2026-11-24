import { documentNavigation } from '../navigation/documentNavigation';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { ApiError, getCsrf, getSession, getPublicProfile, postLogin } from '../api/client';
import { getArticleDetail, setArticleLike } from '../api/articles';
import { getComments } from '../api/comments';
import { article, profile } from '../test/fixtures';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(),
  getSession: vi.fn(), getPublicProfile: vi.fn(), getCsrf: vi.fn(), postLogin: vi.fn() }));
vi.mock('../api/articles', () => ({ getArticleDetail: vi.fn(), setArticleLike: vi.fn() }));
vi.mock('../api/comments', () => ({ getComments: vi.fn() }));

function LocationProbe() {
  const location = useLocation();
  return <output aria-label="当前位置">{location.pathname}</output>;
}

beforeEach(() => {
  vi.mocked(getPublicProfile).mockReset().mockResolvedValue(profile);
  vi.mocked(getSession).mockReset().mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客'));
  vi.mocked(getCsrf).mockReset().mockResolvedValue({ token: 'masked', headerName: 'X-CSRF-TOKEN' });
  vi.mocked(postLogin).mockReset().mockResolvedValue(profile);
  vi.mocked(getArticleDetail).mockReset().mockResolvedValue({ ...article('9007199254740995'), coverMediaId: null, contentHtml: '<p>真实正文</p>' });
  vi.mocked(setArticleLike).mockReset();
  vi.mocked(getComments).mockReset().mockResolvedValue({ items: [], nextCursor: null, hasMore: false, commentCount: 14 });
});

it('详情评论按钮聚焦评论卡片，游客可从卡片登录', async () => {
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /></MemoryRouter>);
  await screen.findByText('真实正文');
  expect(screen.queryByRole('navigation', { name: '文章目录' })).toBeNull();
  expect(screen.getByText('作者简介暂未开放')).toBeVisible();
  await user.click(screen.getByRole('button', { name: '查看评论 14' }));
  expect(screen.getByRole('region', { name: '全部评论 14' })).toHaveFocus();
  expect(screen.getByText('登录后参与讨论')).toBeVisible();
  expect(getComments).toHaveBeenCalledWith('9007199254740995', 'hot', null, expect.any(AbortSignal));
  expect(getArticleDetail).toHaveBeenCalledTimes(1);
});

it('详情锚点在文章加载后定位评论卡片', async () => {
  render(<MemoryRouter initialEntries={['/articles/9007199254740995#comments']}><App /></MemoryRouter>);
  expect(await screen.findByRole('region', { name: '全部评论 14' })).toHaveFocus();
});

it('游客点赞登录后回原文章重新读取状态，不自动补发点赞', async () => {
  vi.mocked(getSession).mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客')).mockResolvedValue(profile);
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /><LocationProbe /></MemoryRouter>);
  const like = await screen.findByRole('button', { name: /^点赞 / });
  await waitFor(() => expect(like).toBeEnabled());
  await user.click(like);
  await screen.findByRole('heading', { name: '账号密码登录' });
  await user.type(screen.getByLabelText('账号'), 'Alice_1');
  await user.type(screen.getByLabelText('密码', { exact: true }), 'password');
  await user.click(screen.getByRole('button', { name: /^登录$/ }));
  await waitFor(() => expect(documentNavigation.replace).toHaveBeenCalledWith('/articles/9007199254740995'));
  // 新文档重新读取由人工交互验收，此处不模拟浏览器重载。
  expect(setArticleLike).not.toHaveBeenCalled();
  expect(getArticleDetail).toHaveBeenCalledTimes(1);
});
