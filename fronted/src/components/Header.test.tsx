import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiError, getCsrf, getSession, getPublicProfile, postLogout } from '../api/client';
import { AuthProvider } from '../auth/AuthContext';
import type { LogoutOptions } from '../auth/useLogout';
import { deferred, profile } from '../test/fixtures';
import { Header } from './Header';
import { ToastProvider } from './Toast';

vi.mock('../api/client', async (original) => ({ ...await original<typeof import('../api/client')>(),
  getSession: vi.fn(), getPublicProfile: vi.fn(), getCsrf: vi.fn(), postLogout: vi.fn() }));
const credentials = { token: 'masked', headerName: 'X-CSRF-TOKEN' };
function LocationProbe() { return <output aria-label="当前路由">{useLocation().pathname}</output>; }
/** 使用真实导航、认证和菜单，只替换接口；StrictMode 同时检查订阅清理。 */
function mount(options: LogoutOptions = {}, path = '/manage/articles') {
  const view = render(<StrictMode><MemoryRouter initialEntries={[path]}><ToastProvider><AuthProvider>
    <Header {...options} /><LocationProbe />
  </AuthProvider></ToastProvider></MemoryRouter></StrictMode>);
  return { ...view, user: userEvent.setup() };
}
async function open(user: ReturnType<typeof userEvent.setup>) {
  await user.hover(await screen.findByRole('button', { name: `${profile.username} 的个人中心` }));
  return screen.findByRole('menu', { name: '用户菜单' });
}
beforeEach(() => {
  vi.mocked(getPublicProfile).mockReset().mockResolvedValue(profile);
  vi.mocked(getSession).mockReset().mockResolvedValue(profile);
  vi.mocked(getCsrf).mockReset().mockResolvedValue(credentials);
  vi.mocked(postLogout).mockReset().mockResolvedValue(undefined);
});
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

it('菜单复用四个图标，个人主页保留入口，管理和设置使用新标签链接', async () => {
  const { user } = mount(); await open(user);
  expect(screen.getAllByRole('menuitem')).toHaveLength(4);
  await user.click(screen.getByRole('menuitem', { name: '个人主页' }));
  const settings = screen.getByRole('menuitem', { name: '设置' });
  expect(settings).toHaveAttribute('href', '/settings/profile');
  expect(settings).toHaveAttribute('target', '_blank');
  expect(settings).toHaveAttribute('rel', 'noopener noreferrer');
  expect(screen.getByLabelText('当前路由')).toHaveTextContent('/manage/articles');
  expect(document.querySelector('.toast')).toBeEmptyDOMElement();
  const manage = screen.getByRole('menuitem', { name: '内容管理' });
  expect(manage).toHaveAttribute('href', '/manage/articles');
  expect(manage).toHaveAttribute('target', '_blank');
  expect(manage).toHaveAttribute('rel', 'noopener noreferrer');
  await user.click(manage);
  expect(screen.queryByRole('menu')).toBeNull();
  expect(screen.getByLabelText('当前路由')).toHaveTextContent('/manage/articles');
  expect(postLogout).not.toHaveBeenCalled();
});

it('键盘打开后聚焦首项，支持循环方向键、首尾与 Esc 归还焦点', async () => {
  const { user } = mount();
  const trigger = await screen.findByRole('button', { name: `${profile.username} 的个人中心` });
  trigger.focus(); await user.keyboard('{Enter}');
  await waitFor(() => expect(screen.getByRole('menuitem', { name: '个人主页' })).toHaveFocus());
  await user.keyboard('{ArrowUp}'); expect(screen.getByRole('menuitem', { name: '退出登录' })).toHaveFocus();
  await user.keyboard('{Home}{ArrowDown}'); expect(screen.getByRole('menuitem', { name: '内容管理' })).toHaveFocus();
  await user.keyboard('{End}'); expect(screen.getByRole('menuitem', { name: '退出登录' })).toHaveFocus();
  await user.keyboard('{Escape}'); expect(trigger).toHaveFocus(); expect(screen.queryByRole('menu')).toBeNull();
  await user.keyboard(' ');
  await waitFor(() => expect(screen.getByRole('menuitem', { name: '个人主页' })).toHaveFocus());
  await user.tab(); expect(screen.queryByRole('menu')).toBeNull();
});

it('悬停离开延迟关闭，经过桥接区保持展开，卸载清理计时与外部监听', async () => {
  vi.useFakeTimers();
  const remove = vi.spyOn(document, 'removeEventListener');
  const { container, unmount } = mount(); await act(async () => {});
  const control = container.querySelector('.avatar-menu-control')!;
  fireEvent.pointerEnter(control, { pointerType: 'mouse' });
  fireEvent.pointerLeave(control); act(() => { vi.advanceTimersByTime(149); });
  expect(screen.getByRole('menu')).toBeVisible();
  fireEvent.pointerEnter(control, { pointerType: 'mouse' }); act(() => { vi.advanceTimersByTime(200); });
  expect(screen.getByRole('menu')).toBeVisible();
  fireEvent.pointerLeave(control); act(() => { vi.advanceTimersByTime(150); });
  expect(screen.queryByRole('menu')).toBeNull();
  fireEvent.pointerEnter(control); fireEvent.pointerDown(document.body);
  expect(screen.queryByRole('menu')).toBeNull();
  fireEvent.pointerEnter(control); fireEvent.pointerLeave(control); unmount();
  expect(remove).toHaveBeenCalledWith('pointerdown', expect.any(Function));
  expect(remove).toHaveBeenCalledWith('keydown', expect.any(Function));
  expect(vi.getTimerCount()).toBe(0);
});

it('头像点击第一次保持悬停菜单，第二次点击关闭', async () => {
  const { user } = mount(); await open(user);
  const trigger = screen.getByRole('button', { name: `${profile.username} 的个人中心` });
  await user.click(trigger); expect(screen.getByRole('menu')).toBeVisible();
  await user.click(trigger); expect(screen.queryByRole('menu')).toBeNull();
});

it('身份未知不显示游客按钮，确认游客后蓝色登录入口仍执行页面登录回调', async () => {
  const pending = deferred<typeof profile>(); vi.mocked(getSession).mockReturnValue(pending.promise);
  const onLogin = vi.fn();
  render(<MemoryRouter><ToastProvider><AuthProvider><Header onLogin={onLogin} /></AuthProvider></ToastProvider></MemoryRouter>);
  expect(screen.getByRole('button', { name: '正在确认登录状态' })).toBeVisible();
  expect(screen.queryByRole('button', { name: /^登录$/ })).toBeNull();
  await act(async () => pending.reject(new ApiError(401, 'AUTHENTICATION_REQUIRED', '游客')));
  const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: /^登录$/ }));
  expect(onLogin).toHaveBeenCalledOnce(); expect(screen.queryByRole('menu')).toBeNull();
});

it('取消编辑器确认不读取凭据、不登出、不导航', async () => {
  const beforeLogout = vi.fn().mockResolvedValue(false);
  const { user } = mount({ beforeLogout }); await open(user);
  await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  expect(beforeLogout).toHaveBeenCalledOnce(); expect(getCsrf).not.toHaveBeenCalled(); expect(postLogout).not.toHaveBeenCalled();
  expect(screen.getByLabelText('当前路由')).toHaveTextContent('/manage/articles');
});

it('登出提交互斥，成功后才执行页面放行并回首页显示登录按钮', async () => {
  const pending = deferred<void>(); vi.mocked(postLogout).mockReturnValue(pending.promise);
  const onLogoutSuccess = vi.fn(); const beforeLogout = vi.fn().mockResolvedValue(true);
  const { user } = mount({ beforeLogout, onLogoutSuccess }); await open(user);
  await user.dblClick(screen.getByRole('menuitem', { name: '退出登录' }));
  expect(postLogout).toHaveBeenCalledTimes(1); expect(onLogoutSuccess).not.toHaveBeenCalled();
  await open(user);
  expect(screen.getByRole('menuitem', { name: '退出中…' })).toBeDisabled();
  expect(postLogout).toHaveBeenCalledWith(credentials, expect.any(AbortSignal));
  await act(async () => pending.resolve());
  await screen.findByRole('button', { name: /^登录$/ });
  expect(onLogoutSuccess).toHaveBeenCalledOnce(); expect(screen.getByLabelText('当前路由')).toHaveTextContent(/^\/$/);
});

it('登出失败保留身份和页面，手动重试成功才放行', async () => {
  vi.mocked(postLogout).mockRejectedValueOnce(new ApiError(503, 'DEPENDENCY_UNAVAILABLE', '失败')).mockResolvedValue(undefined);
  const onLogoutSuccess = vi.fn(); const { user } = mount({ onLogoutSuccess }); await open(user);
  await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  await screen.findByText('服务暂时不可用，请稍后重试');
  expect(onLogoutSuccess).not.toHaveBeenCalled(); expect(screen.getByLabelText('当前路由')).toHaveTextContent('/manage/articles');
  await open(user); await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  await screen.findByRole('button', { name: /^登录$/ }); expect(postLogout).toHaveBeenCalledTimes(2);
});

it('403 只刷新 CSRF，等待再次点击后才重发登出', async () => {
  const fresh = { ...credentials, token: 'fresh' };
  vi.mocked(getCsrf).mockResolvedValueOnce(credentials).mockResolvedValue(fresh);
  vi.mocked(postLogout).mockRejectedValueOnce(new ApiError(403, 'ACCESS_OR_CSRF_DENIED', '失效')).mockResolvedValue(undefined);
  const { user } = mount(); await open(user); await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  await screen.findByText('操作凭证已刷新，请再次点击退出登录'); expect(postLogout).toHaveBeenCalledTimes(1);
  await open(user); await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  await screen.findByRole('button', { name: /^登录$/ });
  expect(postLogout).toHaveBeenLastCalledWith(fresh, expect.any(AbortSignal));
});

it('429 限流期间禁止重复登出，401 则视为已退出', async () => {
  vi.mocked(postLogout).mockRejectedValue(new ApiError(429, 'RATE_LIMITED', '限流', Date.now() + 30_000));
  const { user, unmount } = mount(); await open(user); await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  await screen.findByText('请求过于频繁，请稍后重试');
  expect(screen.getByRole('menuitem', { name: /秒后重试/ })).toBeDisabled(); expect(postLogout).toHaveBeenCalledTimes(1);
  unmount(); vi.mocked(postLogout).mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', '失效'));
  const next = mount(); await open(next.user); await next.user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  await screen.findByRole('button', { name: /^登录$/ }); expect(screen.getByLabelText('当前路由')).toHaveTextContent(/^\/$/);
});
