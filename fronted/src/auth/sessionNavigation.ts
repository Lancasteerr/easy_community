import { loginDestination } from '../article/routes';
import { clearRecovery } from '../editor/recovery';
import { documentNavigation } from '../navigation/documentNavigation';
import { endPage } from './pageLifetime';

/** 只允许已有受保护路由作为登录返回地址，不接受外链或任意查询参数。 */
export function protectedDestination(path: string) {
  const safe = loginDestination(path);
  return safe === '/manage/articles' || safe === '/settings/profile' || safe === '/articles/new'
    || /^\/articles\/[1-9]\d*\/edit$/.test(safe) ? safe : null;
}

export function sessionLoginUrl(path: string) {
  return `/login?${new URLSearchParams({ returnTo: protectedDestination(path) ?? '/', reauth: '1' })}`;
}

/** 登录页只更新身份，其他页面在文档边界清理旧内存；并发401只能导航一次。 */
export function leaveAfterSessionChange() {
  clearRecovery();
  if (window.location.pathname === '/login') return false;
  if (!endPage()) return false;
  if (protectedDestination(window.location.pathname)) documentNavigation.replace(sessionLoginUrl(window.location.pathname));
  else documentNavigation.reload();
  return true;
}

/** 登录成功不复用旧页面及列表快照，也不恢复或重放此前的操作。 */
export function enterAfterLogin(destination: string) {
  if (!endPage()) return;
  clearRecovery();
  documentNavigation.replace(loginDestination(destination));
}
