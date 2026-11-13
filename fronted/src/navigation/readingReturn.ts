import type { ReadingPosition } from './useScrollMemory';

interface ReadingReturnState {
  readingPosition: ReadingPosition;
  /** 仅首页使用公开列表快照，不允许引用管理页等私人快照。 */
  feedSnapshotKey?: string;
}

/** 登录历史状态只接受有限的非负位置，禁止任意对象进入滚动恢复逻辑。 */
export function readingReturnState(destination: string, value: unknown, feedSnapshotKey?: unknown): ReadingReturnState | undefined {
  if ((destination !== '/' && !/^\/articles\/[1-9]\d*$/.test(destination)) || !value || typeof value !== 'object') return;
  const position = value as Partial<ReadingPosition>;
  if (typeof position.top !== 'number' || !Number.isFinite(position.top) || position.top < 0) return;
  const validAnchor = typeof position.anchor === 'string' && /^\d+$/.test(position.anchor)
    && typeof position.offset === 'number' && Number.isFinite(position.offset);
  const validLeft = typeof position.left === 'number' && Number.isFinite(position.left) && position.left >= 0;
  const validFeedKey = destination === '/' && typeof feedSnapshotKey === 'string' && /^feed:[\w-]{1,128}$/.test(feedSnapshotKey);
  return { readingPosition: { top: position.top, ...(validLeft ? { left: position.left } : {}),
    ...(validAnchor ? { anchor: position.anchor, offset: position.offset } : {}) },
    ...(validFeedKey ? { feedSnapshotKey } : {}) };
}
