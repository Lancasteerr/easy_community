import { useCallback, useLayoutEffect, useState, type RefObject } from 'react';
import type { TocEntry } from './content';

/** 标题仅从正文查找，坐标相对滚动框扣除导航遮挡，避免连带横滚及目录滚动。 */
export function useArticleToc(root: RefObject<HTMLElement | null>, content: RefObject<HTMLElement | null>, entries: TocEntry[], topInset = 0) {
  const [active, setActive] = useState(entries[0]?.id);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element || !entries.length) return;
    let frame = 0;
    let disposed = false;
    const update = () => {
      frame = 0;
      const edge = element.getBoundingClientRect().top + topInset + 24;
      let current = entries[0].id;
      for (const entry of entries) {
        const heading = content.current?.querySelector<HTMLElement>(`#${entry.id}`);
        if (heading && heading.getBoundingClientRect().top <= edge) current = entry.id;
      }
      if (element.scrollTop > 0 && element.scrollTop + element.clientHeight >= element.scrollHeight - 2) current = entries.at(-1)!.id;
      setActive(current);
    };
    const schedule = () => { if (!disposed && !frame) frame = requestAnimationFrame(update); };
    update();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(schedule);
    observer?.observe(element);
    if (content.current) observer?.observe(content.current);
    element.addEventListener('scroll', schedule, { passive: true });
    element.addEventListener('load', schedule, true);
    window.addEventListener('resize', schedule);
    void document.fonts?.ready.then(schedule);
    return () => {
      disposed = true; cancelAnimationFrame(frame); observer?.disconnect();
      element.removeEventListener('scroll', schedule);
      element.removeEventListener('load', schedule, true);
      window.removeEventListener('resize', schedule);
    };
  }, [root, content, entries, topInset]);
  const jump = useCallback((id: string) => {
    const element = root.current;
    const heading = content.current?.querySelector<HTMLElement>(`#${id}`);
    if (!element || !heading) return;
    element.scrollTo({ top: element.scrollTop + heading.getBoundingClientRect().top - element.getBoundingClientRect().top - topInset - 24,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  }, [root, content, topInset]);
  return { active, jump };
}
