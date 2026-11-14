import { useCallback, useLayoutEffect, useState, type RefObject } from 'react';

/** 完整卡片始终占据原有文档流；简要卡片只覆盖吸顶区，不通过缩高推动文章。 */
export function useProfileViewport(scroll: RefObject<HTMLDivElement | null>, top: RefObject<HTMLDivElement | null>,
  full: RefObject<HTMLDivElement | null>, enabled: boolean) {
  const [compact, setCompact] = useState(false);
  const getTopInset = useCallback(() => {
    const root = scroll.current;
    const frame = top.current;
    if (!root || !frame) return 167;
    const inset = frame.getBoundingClientRect().height;
    const collapsed = !!full.current && full.current.getBoundingClientRect().bottom <= root.getBoundingClientRect().top + inset;
    return inset + (collapsed ? 88 : 0);
  }, [scroll, top, full]);
  useLayoutEffect(() => {
    const root = scroll.current;
    if (!root) return;
    let animation = 0;
    const sync = () => {
      const inset = getTopInset();
      setCompact(enabled && inset > (top.current?.getBoundingClientRect().height ?? 167));
      root.style.scrollPaddingTop = `${inset}px`;
    };
    const onScroll = () => { if (!animation) animation = requestAnimationFrame(() => { animation = 0; sync(); }); };
    sync();
    root.addEventListener('scroll', onScroll, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(sync);
    observer?.observe(root);
    if (full.current) observer?.observe(full.current);
    return () => { root.removeEventListener('scroll', onScroll); observer?.disconnect(); cancelAnimationFrame(animation); };
  }, [enabled, scroll, top, full, getTopInset]);
  return { compact, getTopInset };
}
