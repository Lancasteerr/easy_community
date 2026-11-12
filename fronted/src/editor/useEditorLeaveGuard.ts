import { useEffect, useRef } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router-dom';

/** 放行标记只用于已成功保存的地址替换与已暂存的登录跳转。 */
export function useEditorLeaveGuard(dirty: boolean, busy: boolean) {
  const allow = useRef(false);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (allow.current) { allow.current = false; return false; }
    return (dirty || busy) && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search);
  });
  useBeforeUnload((event) => { if (dirty || busy) { event.preventDefault(); event.returnValue = ''; } });
  useEffect(() => { if (busy && blocker.state === 'blocked') blocker.reset(); }, [busy, blocker]);
  return { blocker, allowNextNavigation: () => { allow.current = true; } };
}
