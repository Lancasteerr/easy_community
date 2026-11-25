import { useCallback, useState } from 'react';
import type { CommentItem } from '../../api/comments';

/** 一级评论用 null 分组，二级评论用所在楼层 ID 分组。 */
type PublishedByRoot = ReadonlyMap<string | null, readonly CommentItem[]>;
const emptyGroups: PublishedByRoot = new Map();
const emptyItems: readonly CommentItem[] = [];
const REPLY_PREVIEW_SIZE = 2;

/** 常规列表中的同 ID 条目接管内容与位置，临时条目只占用列表前部。 */
export function mergePublishedComments(
  published: readonly CommentItem[] = emptyItems,
  official: readonly CommentItem[] = emptyItems,
) {
  if (!published.length) return official;
  const officialIds = new Set(official.map(({ id }) => id));
  return [...published.filter(({ id }) => !officialIds.has(id)), ...official];
}

/** 预览上限应用于合并结果，展开后保留全部已加载回复。 */
export function visibleReplies(items: readonly CommentItem[], expanded: boolean) {
  return expanded ? items : items.slice(0, REPLY_PREVIEW_SIZE);
}

export function usePublishedComments(articleId: string, userId: string | undefined) {
  const scope = `${articleId}/${userId ?? ''}`;
  const [state, setState] = useState({ scope, byRoot: emptyGroups });
  const current = state.scope === scope;
  // 在本次渲染内切换作用域，避免把旧账号的临时评论带到新页面。
  if (!current) setState({ scope, byRoot: emptyGroups });

  const remember = useCallback((item: CommentItem) => {
    setState((previous) => {
      if (previous.scope !== scope) return previous;
      const group = previous.byRoot.get(item.rootId) ?? emptyItems;
      const byRoot = new Map(previous.byRoot);
      byRoot.set(item.rootId, [item, ...group.filter(({ id }) => id !== item.id)]);
      return { scope, byRoot };
    });
  }, [scope]);

  const reconcile = useCallback((rootId: string | null, official: readonly CommentItem[]) => {
    if (!official.length) return;
    const officialIds = new Set(official.map(({ id }) => id));
    setState((previous) => {
      const group = previous.byRoot.get(rootId);
      if (previous.scope !== scope || !group?.length) return previous;
      const remaining = group.filter(({ id }) => !officialIds.has(id));
      if (remaining.length === group.length) return previous;
      const byRoot = new Map(previous.byRoot);
      if (remaining.length) byRoot.set(rootId, remaining);
      else byRoot.delete(rootId);
      return { scope, byRoot };
    });
  }, [scope]);

  return { byRoot: current ? state.byRoot : emptyGroups, remember, reconcile };
}
