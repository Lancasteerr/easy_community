import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useLogout, type LogoutOptions } from '../auth/useLogout';
import { Avatar } from './Avatar';
import { Icon } from './Icon';
import './avatar-menu.css';

/** 菜单相对 Banner 定位，触发区域与浮层共同管理鼠标、焦点和关闭计时。 */
export function AvatarMenu({ avatarUrl, label, ...options }: LogoutOptions & { avatarUrl?: string | null; label: string }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const animation = useRef<number | undefined>(undefined);
  const clickedOpen = useRef(false);
  const id = useId();
  const logout = useLogout(options);
  const cancelClose = () => clearTimeout(timer.current);
  const close = () => { cancelClose(); clickedOpen.current = false; setOpen(false); };
  const items = () => Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []);
  const focusItem = (last = false) => {
    setOpen(true); clickedOpen.current = true; cancelClose();
    animation.current = requestAnimationFrame(() => { const controls = items(); controls[last ? controls.length - 1 : 0]?.focus({ preventScroll: true }); });
  };
  useEffect(() => () => { clearTimeout(timer.current); if (animation.current !== undefined) cancelAnimationFrame(animation.current); }, []);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) close(); };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); trigger.current?.focus({ preventScroll: true }); }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);

  function menuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const controls = items();
    const index = controls.indexOf(document.activeElement as HTMLElement);
    const target = event.key === 'Home' ? 0 : event.key === 'End' ? controls.length - 1
      : event.key === 'ArrowDown' ? (index + 1) % controls.length
      : event.key === 'ArrowUp' ? (index - 1 + controls.length) % controls.length : undefined;
    if (target !== undefined) { event.preventDefault(); controls[target]?.focus({ preventScroll: true }); }
    // 原生链接不响应空格激活；菜单内与按钮保持相同的键盘语义。
    if (event.key === ' ' && document.activeElement instanceof HTMLAnchorElement) {
      event.preventDefault(); document.activeElement.click();
    }
  }
  return <div className="avatar-menu-control" ref={root}
    onMouseDown={(event) => {
      // 吸顶导航中的原生焦点滚动会把通栏拉回顶部；保留焦点而不改动阅读位置。
      const control = (event.target as Element).closest<HTMLElement>('button, a');
      if (event.button === 0 && control && !control.matches(':disabled')) { event.preventDefault(); control.focus({ preventScroll: true }); }
    }}
    onPointerEnter={(event) => { if (event.pointerType !== 'touch') { cancelClose(); setOpen(true); } }}
    onPointerLeave={() => { cancelClose(); timer.current = setTimeout(close, 150); }}
    onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) close(); }}>
    <button type="button" className="avatar-entry icon-button" ref={trigger} aria-label={label}
      aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => { if (clickedOpen.current) close(); else { cancelClose(); clickedOpen.current = true; setOpen(true); } }}
      onKeyDown={(event) => {
        if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); focusItem(event.key === 'ArrowUp'); }
      }}><Avatar src={avatarUrl} size={40} /></button>
    {open && <div className="avatar-menu-positioner">
      <div id={id} className="avatar-menu" role="menu" aria-label="用户菜单" ref={menu} onKeyDown={menuKeyDown}>
        <button type="button" role="menuitem" tabIndex={-1} className="avatar-menu-item"><Icon name="menuHome" />个人主页</button>
        <a role="menuitem" tabIndex={-1} className="avatar-menu-item" href="/manage/articles" target="_blank" rel="noopener noreferrer" onClick={close}>
          <Icon name="menuContent" />内容管理</a>
        <a role="menuitem" tabIndex={-1} className="avatar-menu-item" href="/settings/profile" target="_blank" rel="noopener noreferrer" onClick={close}>
          <Icon name="menuSettings" />设置</a>
        <div className="avatar-menu-divider" role="separator" />
        <button type="button" role="menuitem" tabIndex={-1} className="avatar-menu-item" disabled={logout.busy || logout.confirming || logout.seconds > 0}
          aria-busy={logout.busy} onClick={() => { void logout.logout(); }}><Icon name="menuLogout" />
          {logout.busy ? '退出中…' : logout.seconds ? `${logout.seconds}秒后重试` : '退出登录'}</button>
      </div>
    </div>}
  </div>;
}
