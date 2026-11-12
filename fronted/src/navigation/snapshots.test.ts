import { expect, it } from 'vitest';
import { readSnapshot, saveSnapshot, setSnapshotOwner } from './snapshots';

it('历史项分开保存，账号切换清除私人数据并拒绝旧会话迟到写入', () => {
  setSnapshotOwner('a');
  saveSnapshot('public-history', [1]);
  saveSnapshot('private-history', [2], 'a');
  expect(readSnapshot('private-history', 'a')).toEqual([2]);
  expect(readSnapshot('private-history', 'b')).toBeUndefined();
  setSnapshotOwner('b');
  saveSnapshot('private-history', [3], 'a');
  expect(readSnapshot('private-history', 'b')).toBeUndefined();
  expect(readSnapshot('public-history')).toEqual([1]);
  setSnapshotOwner(null);
});
