import { useEffect } from 'react';
import { clearRecovery } from '../editor/recovery';
import { documentNavigation } from '../navigation/documentNavigation';
import { endPage } from './pageLifetime';
import { sessionLoginUrl } from './sessionNavigation';

/** 初次游客访问受保护页时进入显式登录，不在未知身份或服务故障时跳转。 */
export function SessionLoginRedirect({ returnTo }: { returnTo: string }) {
  useEffect(() => {
    if (!endPage()) return;
    clearRecovery();
    documentNavigation.replace(sessionLoginUrl(returnTo));
  }, [returnTo]);
  return null;
}
