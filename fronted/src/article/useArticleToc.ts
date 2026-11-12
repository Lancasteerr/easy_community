import { useCallback, useLayoutEffect, useState, type RefObject } from 'react';
import type { TocEntry } from './content';

/** 章节位置始终以阅读容器计算，避免 scrollIntoView 连带滚动页面和右栏。 */
export function useArticleToc(root: RefObject<HTMLElement | null>, entries: TocEntry[]) {
  const [active, setActive] = useState(entries[0]?.id);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element || !entries.length) return;
    let frame = 0;
    let disposed = false;
    const update = () => {
      frame = 0;
      const edge = element.getBoundingClientRect().top + 25;
      let current = entries[0].id;
      for (const entry of entries) {
        const heading = element.querySelector<HTMLElement>(`#${entry.id}`);
        if (heading && heading.getBoundingClientRect().top <= edge) current = entry.id;
      }
      if (element.scrollTop > 0 && element.scrollTop + element.clientHeight >= element.scrollHeight - 2) current = entries.at(-1)!.id;
      setActive(current);
    };
    const schedule = () => { if (!disposed && !frame) frame = requestAnimationFrame(update); };
    update();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(schedule);
    observer?.observe(element);
    if (element.firstElementChild) observer?.observe(element.firstElementChild);
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
  }, [root, entries]);
  const jump = useCallback((id: string) => {
    const element = root.current;
    const heading = element?.querySelector<HTMLElement>(`#${id}`);
    if (!element || !heading) return;
    element.scrollTo({ top: element.scrollTop + heading.getBoundingClientRect().top - element.getBoundingClientRect().top - 24,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  }, [root]);
  return { active, jump };
}
