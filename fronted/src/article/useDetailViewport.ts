import { useLayoutEffect, useState, type RefObject } from 'react';

/** 尺寸只在布局变化时同步；滚动与顶部遮挡完全交由浏览器处理。 */
export function useDetailViewport(scroll: RefObject<HTMLDivElement | null>, top: RefObject<HTMLDivElement | null>) {
  const [topInset, setTopInset] = useState(167);
  useLayoutEffect(() => {
    const container = scroll.current;
    const frame = top.current;
    if (!container || !frame) return;
    const syncSize = () => {
      const inset = frame.getBoundingClientRect().height;
      // clientHeight 已扣除横向滚动条占用，右栏和短文章使用同一可见高度。
      container.style.setProperty('--detail-viewport-height', `${container.clientHeight}px`);
      container.style.setProperty('--detail-top-height', `${inset}px`);
      setTopInset(inset);
    };
    syncSize();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(syncSize);
    observer?.observe(container);
    observer?.observe(frame);
    window.addEventListener('resize', syncSize);
    return () => { observer?.disconnect(); window.removeEventListener('resize', syncSize); };
  }, [scroll, top]);
  return topInset;
}
