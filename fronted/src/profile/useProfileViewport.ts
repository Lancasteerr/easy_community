import { useCallback, useLayoutEffect, type RefObject } from 'react';
import { profileCollapseAt, profileLayout } from './profileCollapse';

/** 原高度占位保留；只更新固定资料卡的裁切高度和内部变换，不逐帧重渲染React。 */
export function useProfileViewport(scroll: RefObject<HTMLDivElement | null>, top: RefObject<HTMLDivElement | null>,
  enabled: boolean) {
  const getTopInset = useCallback(() => {
    const root = scroll.current;
    const frame = top.current;
    if (!root || !frame) return 167;
    const inset = frame.getBoundingClientRect().height;
    if (!enabled) return inset;
    const heading = root.querySelector<HTMLElement>('.profile-list-heading');
    // 发布栏会承接吸顶，文章锚点恢复必须同时扣除资料卡、间隔与发布栏。
    return inset + profileCollapseAt(root.scrollTop).height + profileLayout.lowerGap + (heading?.offsetHeight ?? 0);
  }, [scroll, top, enabled]);
  useLayoutEffect(() => {
    const root = scroll.current;
    if (!root) return;
    let animation = 0;
    const sync = () => {
      const state = profileCollapseAt(enabled ? root.scrollTop : 0);
      const frameHeight = top.current?.getBoundingClientRect().height ?? 167;
      const variables: Record<string, string> = {
        '--profile-card-height': `${state.height}px`,
        '--profile-list-top': `${frameHeight + state.height + profileLayout.lowerGap}px`,
        '--profile-cover-y': `${state.coverY}px`,
        '--profile-cover-visibility': state.coverY <= -profileLayout.coverHeight ? 'hidden' : 'visible',
        '--profile-avatar-y': `${state.avatarY}px`,
        '--profile-avatar-scale': `${state.avatarScale}`,
        '--profile-name-x': `${state.nameX}px`,
        '--profile-name-y': `${state.nameY}px`,
        '--profile-name-scale': `${state.nameScale}`,
        '--profile-bio-y': `${state.bioY}px`,
        '--profile-bio-opacity': `${state.bioOpacity}`,
        '--profile-bio-visibility': state.bioOpacity === 0 ? 'hidden' : 'visible',
        '--profile-stats-y': `${state.statsY}px`,
        '--profile-stats-scale': `${state.statsScale}`,
        '--profile-action-y': `${state.actionY}px`,
      };
      for (const [name, value] of Object.entries(variables)) root.style.setProperty(name, value);
      root.dataset.profileCollapsed = String(enabled && state.collapsed);
      root.style.scrollPaddingTop = `${getTopInset()}px`;
    };
    const onScroll = () => { if (!animation) animation = requestAnimationFrame(() => { animation = 0; sync(); }); };
    sync();
    root.addEventListener('scroll', onScroll, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(sync);
    observer?.observe(root);
    if (top.current) observer?.observe(top.current);
    return () => { root.removeEventListener('scroll', onScroll); observer?.disconnect(); cancelAnimationFrame(animation); };
  }, [enabled, scroll, top, getTopInset]);
  return { getTopInset };
}
