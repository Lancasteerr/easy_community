import { useCallback, useLayoutEffect, useRef, type RefObject } from 'react';
import { readSnapshot, saveSnapshot } from './snapshots';

export interface ReadingPosition { top: number; left?: number; anchor?: string; offset?: number }

/** 内容范围与滚动框分离；遮挡高度以可见阅读区顶部为基准。 */
export interface ReadingGeometry {
  contentRef?: RefObject<HTMLElement | null>;
  topInset?: number;
}

/** 使用内容块锚点及相对位置恢复，图片在锚点上方变高时仍能保持阅读进度。 */
export function capturePosition(root: HTMLElement | null, geometry: ReadingGeometry = {}): ReadingPosition {
  if (!root) return { top: 0 };
  const edge = root.getBoundingClientRect().top + (geometry.topInset ?? 0);
  const blocks = Array.from((geometry.contentRef?.current ?? root).querySelectorAll<HTMLElement>('[data-read-block]'));
  const anchor = blocks.find((block) => block.getBoundingClientRect().bottom > edge);
  return { top: root.scrollTop, left: root.scrollLeft, anchor: anchor?.dataset.readBlock,
    offset: anchor ? anchor.getBoundingClientRect().top - edge : undefined };
}

/** 仅恢复当前历史项；用户开始操作后停止布局校正，避免与手动阅读争抢位置。 */
export function useScrollMemory<T extends HTMLElement>(root: RefObject<T | null>, key: string,
  ready: boolean, initial?: ReadingPosition, owner?: string, revision = 0, geometry: ReadingGeometry = {}) {
  const { contentRef, topInset = 0 } = geometry;
  const previousRevision = useRef(revision);
  const stopRestoring = useRef<() => void>(() => {});
  useLayoutEffect(() => {
    const element = root.current;
    if (!element || !ready) return;
    const content = contentRef?.current ?? element;
    const reset = previousRevision.current !== revision;
    previousRevision.current = revision;
    const position = reset ? { top: 0 } : readSnapshot<ReadingPosition>(key, owner) ?? initial ?? { top: 0 };
    let lastPosition = position;
    let restoring = true;
    let disposed = false;
    const restore = () => {
      if (!restoring || disposed) return;
      const anchor = position.anchor === undefined ? undefined : Array.from(content.querySelectorAll<HTMLElement>('[data-read-block]'))
        .find((block) => block.dataset.readBlock === position.anchor);
      element.scrollTop = anchor && position.offset !== undefined
        ? element.scrollTop + anchor.getBoundingClientRect().top - element.getBoundingClientRect().top - topInset - position.offset : position.top;
      // 首页通栏同时承接横向浏览；旧快照或显式回顶不改变当前横向位置。
      if (position.left !== undefined) element.scrollLeft = position.left;
    };
    const save = () => {
      lastPosition = capturePosition(element, { contentRef, topInset });
      saveSnapshot(key, lastPosition, owner);
    };
    const interact = () => { restoring = false; save(); };
    stopRestoring.current = interact;
    const onKey = (event: KeyboardEvent) => {
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) interact();
    };
    restore();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(restore);
    observer?.observe(element);
    observer?.observe(contentRef ? content : element.firstElementChild ?? element);
    element.addEventListener('load', restore, true);
    window.addEventListener('resize', restore);
    element.addEventListener('scroll', save, { passive: true });
    element.addEventListener('wheel', interact, { passive: true });
    element.addEventListener('touchstart', interact, { passive: true });
    element.addEventListener('pointerdown', interact);
    element.addEventListener('keydown', onKey);
    void document.fonts?.ready.then(restore);
    return () => {
      // 祖先节点卸载后正文坐标可能已归零；保留最后有效采集，不能用卸载坐标覆盖锚点。
      saveSnapshot(key, lastPosition, owner); disposed = true; observer?.disconnect();
      element.removeEventListener('load', restore, true);
      window.removeEventListener('resize', restore);
      element.removeEventListener('scroll', save);
      element.removeEventListener('wheel', interact);
      element.removeEventListener('touchstart', interact);
      element.removeEventListener('pointerdown', interact);
      element.removeEventListener('keydown', onKey);
    };
  }, [root, key, ready, initial, owner, revision, contentRef, topInset]);
  // 目录位于阅读容器外，显式章节跳转也应终止返回页面时的自动校正。
  return useCallback(() => stopRestoring.current(), []);
}
