import { documentNavigation } from '../navigation/documentNavigation';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { ApiError, getCsrf, getSession, getPublicProfile, postLogin } from '../api/client';
import { getArticleDetail, setArticleLike } from '../api/articles';
import { getComments } from '../api/comments';
import defaultAvatar from '../assets/default-avatar.jpg';
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
  vi.mocked(getArticleDetail).mockReset().mockResolvedValue({
    ...article('9007199254740995'), authorBio: '', coverMediaId: null, contentHtml: '<p>真实正文</p>',
  });
  vi.mocked(setArticleLike).mockReset();
  vi.mocked(getComments).mockReset().mockResolvedValue({ items: [], nextCursor: null, hasMore: false, commentCount: 14 });
});

it('详情评论按钮聚焦评论卡片，游客可从卡片登录', async () => {
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /></MemoryRouter>);
  await screen.findByText('真实正文');
  expect(screen.queryByRole('navigation', { name: '文章目录' })).toBeNull();
  await user.click(screen.getByRole('button', { name: '查看评论 14' }));
  expect(screen.getByRole('region', { name: '全部评论 14' })).toHaveFocus();
  const prompt = screen.getByText('登录后参与讨论');
  expect(prompt).toBeVisible();
  expect(prompt.parentElement?.querySelector('img')).toHaveAttribute('src', defaultAvatar);
  expect(getComments).toHaveBeenCalledWith('9007199254740995', 'hot', null, expect.any(AbortSignal));
  expect(getArticleDetail).toHaveBeenCalledTimes(1);
});

it('导航和两个评论编辑器共用当前登录账号的头像资料', async () => {
  const viewer = { ...profile, userId: '7', username: '当前账号', avatarUrl: '/viewer-avatar.png' };
  vi.mocked(getSession).mockResolvedValue(viewer);
  vi.mocked(getPublicProfile).mockResolvedValue(viewer);
  vi.mocked(getComments).mockResolvedValue({
    items: [{ id: '1', rootId: null, author: { id: '8', username: '评论作者' },
      content: '已有评论', createdAt: viewer.createdAt }],
    nextCursor: null, hasMore: false, commentCount: 1,
  });
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /></MemoryRouter>);

  const account = await screen.findByRole('button', { name: '当前账号 的个人中心' });
  const editor = await screen.findByRole('textbox', { name: '写下你的想法，参与讨论' });
  expect(account.querySelector('img')).toHaveAttribute('src', viewer.avatarUrl);
  expect(editor.closest('form')?.querySelector('img')).toHaveAttribute('src', viewer.avatarUrl);
  await userEvent.click(await screen.findByRole('button', { name: /^回复$/ }));
  const reply = await screen.findByRole('textbox', { name: '回复 @评论作者' });
  expect(reply.closest('form')?.querySelector('img')).toHaveAttribute('src', viewer.avatarUrl);
  expect(getPublicProfile).toHaveBeenCalledExactlyOnceWith(viewer.userId, expect.any(AbortSignal));
});

it.each([
  { bio: '<b>社区作者</b>', text: '<b>社区作者</b>' },
  { bio: '', text: '暂无简介' },
])('游客从详情响应读取作者简介，显示 $text', async ({ bio, text }) => {
  vi.mocked(getArticleDetail).mockResolvedValueOnce({
    ...article('9007199254740995'), authorBio: bio, coverMediaId: null, contentHtml: '<p>真实正文</p>',
  });
  render(<MemoryRouter initialEntries={['/articles/9007199254740995']}><App /></MemoryRouter>);

  const authorCard = await screen.findByRole('region', { name: '文章作者' });
  // 作者姓名仅在作者卡片中显示，标题下方不再重复展示。
  expect(authorCard).toContainElement(screen.getByText('Flame', { exact: true }));
  // 简介按纯文本展示，详情页直接复用详情响应中的作者资料。
  expect(within(authorCard).getByText(text)).toBeVisible();
  expect(getPublicProfile).not.toHaveBeenCalled();
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
