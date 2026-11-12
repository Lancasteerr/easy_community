import { useCallback, useLayoutEffect, useRef, type RefObject } from 'react';
import { readSnapshot, saveSnapshot } from './snapshots';

export interface ReadingPosition { top: number; anchor?: string; offset?: number }

/** 使用内容块锚点及相对位置恢复，图片在锚点上方变高时仍能保持阅读进度。 */
export function capturePosition(root: HTMLElement | null): ReadingPosition {
  if (!root) return { top: 0 };
  const edge = root.getBoundingClientRect().top;
  const blocks = Array.from(root.querySelectorAll<HTMLElement>('[data-read-block]'));
  const anchor = blocks.find((block) => block.getBoundingClientRect().bottom > edge);
  return { top: root.scrollTop, anchor: anchor?.dataset.readBlock,
    offset: anchor ? anchor.getBoundingClientRect().top - edge : undefined };
}

/** 仅恢复当前历史项；用户开始操作后停止布局校正，避免与手动阅读争抢位置。 */
export function useScrollMemory<T extends HTMLElement>(root: RefObject<T | null>, key: string,
  ready: boolean, initial?: ReadingPosition, owner?: string, revision = 0) {
  const previousRevision = useRef(revision);
  const stopRestoring = useRef<() => void>(() => {});
  useLayoutEffect(() => {
    const element = root.current;
    if (!element || !ready) return;
    const reset = previousRevision.current !== revision;
    previousRevision.current = revision;
    const position = reset ? { top: 0 } : readSnapshot<ReadingPosition>(key, owner) ?? initial ?? { top: 0 };
    let restoring = true;
    let disposed = false;
    const restore = () => {
      if (!restoring || disposed) return;
      const anchor = position.anchor === undefined ? undefined : Array.from(element.querySelectorAll<HTMLElement>('[data-read-block]'))
        .find((block) => block.dataset.readBlock === position.anchor);
      element.scrollTop = anchor && position.offset !== undefined
        ? element.scrollTop + anchor.getBoundingClientRect().top - element.getBoundingClientRect().top - position.offset : position.top;
    };
    const save = () => saveSnapshot(key, capturePosition(element), owner);
    const interact = () => { restoring = false; };
    stopRestoring.current = interact;
    const onKey = (event: KeyboardEvent) => {
      if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) interact();
    };
    restore();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(restore);
    observer?.observe(element);
    if (element.firstElementChild) observer?.observe(element.firstElementChild);
    element.addEventListener('load', restore, true);
    element.addEventListener('scroll', save, { passive: true });
    element.addEventListener('wheel', interact, { passive: true });
    element.addEventListener('touchstart', interact, { passive: true });
    element.addEventListener('pointerdown', interact);
    element.addEventListener('keydown', onKey);
    void document.fonts?.ready.then(restore);
    return () => {
      save(); disposed = true; observer?.disconnect();
      element.removeEventListener('load', restore, true);
      element.removeEventListener('scroll', save);
      element.removeEventListener('wheel', interact);
      element.removeEventListener('touchstart', interact);
      element.removeEventListener('pointerdown', interact);
      element.removeEventListener('keydown', onKey);
    };
  }, [root, key, ready, initial, owner, revision]);
  // 目录位于阅读容器外，显式章节跳转也应终止返回页面时的自动校正。
  return useCallback(() => stopRestoring.current(), []);
}
