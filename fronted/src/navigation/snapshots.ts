interface Snapshot { value: unknown; owner?: string }
const snapshots = new Map<string, Snapshot>();
let currentOwner: string | null = null;

/** 标签页内最多保留二十个历史位置及列表会话；整页刷新自然清空，不存正文。 */
export function saveSnapshot<T>(key: string | undefined, value: T, owner?: string) {
  if (!key || (owner !== undefined && owner !== currentOwner)) return;
  snapshots.delete(key);
  snapshots.set(key, { value, owner });
  if (snapshots.size > 20) snapshots.delete(snapshots.keys().next().value!);
}

export function readSnapshot<T>(key: string | undefined, owner?: string): T | undefined {
  if (!key || (owner !== undefined && owner !== currentOwner)) return;
  const entry = snapshots.get(key);
  return entry && entry.owner === owner ? entry.value as T : undefined;
}

/** 已确认的身份变化时清理私人列表，迟到的旧账号清理回调也无法重新写入。 */
export function setSnapshotOwner(owner: string | null) {
  if (owner === currentOwner) return;
  currentOwner = owner;
  for (const [key, entry] of snapshots) if (entry.owner !== undefined) snapshots.delete(key);
}
