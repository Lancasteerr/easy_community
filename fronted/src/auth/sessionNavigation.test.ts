import { expect, it } from 'vitest';
import { documentNavigation } from '../navigation/documentNavigation';
import { recoveryKey } from '../editor/recovery';
import { capturePage } from './pageLifetime';
import { enterAfterLogin, leaveAfterSessionChange, protectedDestination } from './sessionNavigation';

it.each(['/', '/articles/9007199254740995?from=feed#section'])('公开页身份变化保留当前地址 %s，且只刷新一次', (path) => {
  window.history.replaceState(null, '', path);
  const before = window.location.href;
  const active = capturePage();
  sessionStorage.setItem(recoveryKey, '旧编辑内容');
  expect(leaveAfterSessionChange()).toBe(true);
  expect(leaveAfterSessionChange()).toBe(false);
  expect(documentNavigation.reload).toHaveBeenCalledTimes(1);
  expect(documentNavigation.replace).not.toHaveBeenCalled();
  expect(window.location.href).toBe(before);
  expect(active()).toBe(false);
  expect(sessionStorage.getItem(recoveryKey)).toBeNull();
});

it.each(['/manage/articles', '/articles/new', '/articles/12/edit', '/settings/profile'])('受保护页 %s 进入显式登录并保留返回地址', (path) => {
  window.history.replaceState(null, '', path);
  leaveAfterSessionChange();
  expect(documentNavigation.replace).toHaveBeenCalledExactlyOnceWith(`/login?${new URLSearchParams({ returnTo: path, reauth: '1' })}`);
  expect(documentNavigation.reload).not.toHaveBeenCalled();
});

it('登录页不循环导航，非法返回地址不能成为重定向目标', () => {
  window.history.replaceState(null, '', '/login');
  expect(leaveAfterSessionChange()).toBe(false);
  expect(documentNavigation.reload).not.toHaveBeenCalled();
  expect(protectedDestination('https://example.com')).toBeNull();
  enterAfterLogin('//example.com');
  expect(documentNavigation.replace).toHaveBeenCalledExactlyOnceWith('/');
});
