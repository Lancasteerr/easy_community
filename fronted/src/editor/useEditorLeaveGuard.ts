import { useCallback, useEffect, useRef, useState } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router-dom';

export interface EditorLogoutGuard {
  confirm: () => Promise<boolean>;
  complete: () => void;
}

/** 放行标记只用于保存、暂存后跳转，以及已确认成功的登出。 */
export function useEditorLeaveGuard(dirty: boolean, busy: boolean) {
  const allow = useRef(false);
  const current = useRef({ dirty, busy }); current.current = { dirty, busy };
  const [confirmingLogout, setConfirmingLogout] = useState(false);
  const waiting = useRef<((accepted: boolean) => void) | null>(null);
  const allowNextNavigation = useCallback(() => { allow.current = true; }, []);
  const confirmLogout = useCallback(() => {
    if (current.current.busy || waiting.current) return Promise.resolve(false);
    if (!current.current.dirty) return Promise.resolve(true);
    setConfirmingLogout(true);
    return new Promise<boolean>((resolve) => { waiting.current = resolve; });
  }, []);
  const resolveLogout = useCallback((accepted: boolean) => {
    const resolve = waiting.current; waiting.current = null;
    setConfirmingLogout(false); resolve?.(accepted);
  }, []);
  // 卸载或进入保存期间不能让未完成的确认继续发送登出请求。
  useEffect(() => () => { waiting.current?.(false); waiting.current = null; }, []);
  useEffect(() => { if (busy) resolveLogout(false); }, [busy, resolveLogout]);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (allow.current) { allow.current = false; return false; }
    return (dirty || busy) && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search);
  });
  useBeforeUnload((event) => { if (dirty || busy) { event.preventDefault(); event.returnValue = ''; } });
  useEffect(() => { if (busy && blocker.state === 'blocked') blocker.reset(); }, [busy, blocker]);
  return { blocker, allowNextNavigation, confirmLogout, confirmingLogout, resolveLogout };
}
