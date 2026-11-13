import type { ReadingPosition } from './useScrollMemory';

/** 登录历史状态只接受有限的非负位置，禁止任意对象进入滚动恢复逻辑。 */
export function readingReturnState(destination: string, value: unknown): { readingPosition: ReadingPosition } | undefined {
  if (!/^\/articles\/[1-9]\d*$/.test(destination) || !value || typeof value !== 'object') return;
  const position = value as Partial<ReadingPosition>;
  if (typeof position.top !== 'number' || !Number.isFinite(position.top) || position.top < 0) return;
  const validAnchor = typeof position.anchor === 'string' && /^\d+$/.test(position.anchor)
    && typeof position.offset === 'number' && Number.isFinite(position.offset);
  const validLeft = typeof position.left === 'number' && Number.isFinite(position.left) && position.left >= 0;
  return { readingPosition: { top: position.top, ...(validLeft ? { left: position.left } : {}),
    ...(validAnchor ? { anchor: position.anchor, offset: position.offset } : {}) } };
}
