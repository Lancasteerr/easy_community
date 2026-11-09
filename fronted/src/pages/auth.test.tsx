import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getCsrf, getProfile, postLogin } from '../api/client';
import { App } from '../App';
import { deferred, profile } from '../test/fixtures';
import type { UserIdentity } from '../api/types';

vi.mock('../api/client', async (original) => ({
  ...await original<typeof import('../api/client')>(), getProfile: vi.fn(), getCsrf: vi.fn(), postLogin: vi.fn(),
}));
// 分页逻辑已单独测试，此处只关注真实路由、登录态与表单的协作。
vi.mock('../components/Feed', () => ({ Feed: () => <section aria-label="推荐文章" /> }));

const readProfile = vi.mocked(getProfile);
const readCsrf = vi.mocked(getCsrf);
const login = vi.mocked(postLogin);
const token = { token: 'masked-token', headerName: 'X-CSRF-TOKEN' };

function LocationProbe() { return <output aria-label="当前路由">{useLocation().pathname}</output>; }
function mount(path = '/login') {
  render(<MemoryRouter initialEntries={[path]}><App /><LocationProbe /></MemoryRouter>);
  return userEvent.setup();
}
async function fill(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('账号'), '  Alice_1  ');
  await user.type(screen.getByLabelText('密码', { exact: true }), '  passWord123  ');
}

beforeEach(() => {
  readProfile.mockReset().mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客'));
  readCsrf.mockReset().mockResolvedValue(token);
  login.mockReset().mockResolvedValue(profile);
});

describe('登录页', () => {
  it('空表单按账号、密码顺序校验并聚焦，不请求 CSRF 或认证接口', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: '登录' }));
    expect(screen.getByLabelText('账号')).toHaveFocus();
    expect(screen.getByLabelText('账号')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('请输入账号', { selector: '.login-feedback' })).toBeVisible();
    await user.type(screen.getByLabelText('账号'), 'Alice_1');
    await user.click(screen.getByRole('button', { name: '登录' }));
    expect(screen.getByLabelText('密码', { exact: true })).toHaveFocus();
    expect(readCsrf).not.toHaveBeenCalled();
    expect(login).not.toHaveBeenCalled();
  });

  it('密码显隐不改变输入内容，回车提交保留密码并统一展示认证错误', async () => {
    login.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', '凭证错误'));
    const user = mount();
    await fill(user);
    await user.click(screen.getByRole('button', { name: '显示密码' }));
    expect(screen.getByLabelText('密码', { exact: true })).toHaveAttribute('type', 'text');
    expect(screen.getByLabelText('密码', { exact: true })).toHaveValue('  passWord123  ');
    await user.click(screen.getByRole('button', { name: '隐藏密码' }));
    await user.click(screen.getByLabelText('密码', { exact: true }));
    await user.keyboard('{Enter}');
    await screen.findByText('账号或密码错误，请重试');
    expect(login).toHaveBeenCalledWith('Alice_1', '  passWord123  ', token, expect.any(AbortSignal));
    expect(screen.getByLabelText('账号')).toHaveValue('  Alice_1  ');
    expect(screen.getByLabelText('密码', { exact: true })).toHaveValue('  passWord123  ');
  });

  it('提交中锁定表单，重复点击和回车不重复提交', async () => {
    const pending = deferred<UserIdentity>();
    login.mockReturnValue(pending.promise);
    const user = mount();
    await fill(user);
    await user.dblClick(screen.getByRole('button', { name: '登录' }));
    expect(screen.getByLabelText('账号')).toBeDisabled();
    expect(screen.getByLabelText('密码', { exact: true })).toBeDisabled();
    expect(screen.getByRole('button', { name: '显示密码' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '登录中…' })).toBeDisabled();
    await user.keyboard('{Enter}');
    expect(login).toHaveBeenCalledTimes(1);
    await act(async () => pending.reject(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '不可用')));
    expect(await screen.findByText('服务暂时不可用，请稍后重试')).toBeVisible();
    expect(screen.getByLabelText('账号')).toBeEnabled();
  });

  it('CSRF 失败后刷新令牌，只在用户再次提交时重发登录', async () => {
    login.mockRejectedValueOnce(new ApiError(403, 'ACCESS_OR_CSRF_DENIED', 'CSRF失效')).mockResolvedValueOnce(profile);
    readCsrf.mockResolvedValueOnce(token).mockResolvedValue({ ...token, token: 'fresh' });
    readProfile.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客')).mockResolvedValue(profile);
    const user = mount();
    await fill(user);
    await user.click(screen.getByRole('button', { name: '登录' }));
    await screen.findByText('登录凭据已刷新，请再次点击登录');
    expect(login).toHaveBeenCalledTimes(1);
    expect(readCsrf).toHaveBeenCalledTimes(2);
    await user.click(screen.getByRole('button', { name: '登录' }));
    await waitFor(() => expect(screen.getByLabelText('当前路由')).toHaveTextContent(/^\/$/));
    expect(login).toHaveBeenLastCalledWith('Alice_1', '  passWord123  ', { ...token, token: 'fresh' }, expect.any(AbortSignal));
  });

  it('429 按 Retry-After 倒计时禁用提交，保留输入', async () => {
    login.mockRejectedValue(new ApiError(429, 'RATE_LIMITED', '限流', Date.now() + 30_000));
    const user = mount();
    await fill(user);
    await user.click(screen.getByRole('button', { name: '登录' }));
    await screen.findByText('请求过于频繁，请稍后重试');
    expect(screen.getByRole('button', { name: /秒后重试/ })).toBeDisabled();
    expect(screen.getByLabelText('账号')).toBeEnabled();
    expect(login).toHaveBeenCalledTimes(1);
  });

  it('登录成功刷新 CSRF 与资料，替换路由返回主页', async () => {
    readProfile.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客')).mockResolvedValue(profile);
    const user = mount();
    await fill(user);
    await user.click(screen.getByRole('button', { name: '登录' }));
    await screen.findByRole('button', { name: `${profile.username} 的个人中心` });
    expect(screen.getByLabelText('当前路由')).toHaveTextContent(/^\/$/);
    expect(readCsrf).toHaveBeenCalledTimes(2);
    expect(readProfile).toHaveBeenCalledTimes(2);
    expect(login).toHaveBeenCalledTimes(1);
  });

  it('登录后资料和 CSRF 刷新失败不撤销成功、不重复认证', async () => {
    readProfile.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客')).mockRejectedValue(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败'));
    readCsrf.mockResolvedValueOnce(token).mockRejectedValue(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败'));
    const user = mount();
    await fill(user);
    await user.click(screen.getByRole('button', { name: '登录' }));
    await screen.findByRole('button', { name: `${profile.username} 的个人中心` });
    expect(screen.getByText('登录成功，部分资料暂时无法刷新')).toBeVisible();
    expect(login).toHaveBeenCalledTimes(1);
  });

  it('返回首页时取消进行中的登录请求', async () => {
    login.mockReturnValue(new Promise(() => {}));
    const user = mount();
    await fill(user);
    await user.click(screen.getByRole('button', { name: '登录' }));
    const signal = login.mock.calls[0][3]!;
    await user.click(screen.getByRole('link', { name: '返回首页' }));
    expect(signal.aborted).toBe(true);
    expect(screen.getByLabelText('当前路由')).toHaveTextContent(/^\/$/);
  });
});

describe('主页登录态', () => {
  it('游客点击顶部头像进入登录页', async () => {
    const user = mount('/');
    await user.click(await screen.findByRole('button', { name: '登录' }));
    expect(screen.getByLabelText('当前路由')).toHaveTextContent('/login');
  });

  it('已登录点击头像提示个人中心未开放，直接访问登录页返回主页', async () => {
    readProfile.mockResolvedValue(profile);
    const user = mount();
    await user.click(await screen.findByRole('button', { name: `${profile.username} 的个人中心` }));
    expect(screen.getByText('个人中心暂未开放')).toBeVisible();
    expect(readCsrf).not.toHaveBeenCalled();
  });

  it('资料查询失败保留未知态，点击重试成功后才判定游客', async () => {
    readProfile.mockRejectedValueOnce(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败'))
      .mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客'));
    const user = mount('/');
    await user.click(await screen.findByRole('button', { name: '重新检查登录状态' }));
    expect(screen.getByLabelText('当前路由')).toHaveTextContent(/^\/$/);
    await screen.findByRole('button', { name: '登录' });
    expect(readProfile).toHaveBeenCalledTimes(2);
  });
});
