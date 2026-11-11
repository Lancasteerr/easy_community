import { useEffect, useLayoutEffect, useRef } from 'react';
import { useCooldown } from '../../hooks/useCooldown';
import type { useMyArticles } from '../../hooks/useMyArticles';
import type { ArticleCardData } from '../../api/types';
import { ManageArticleRow } from './ManageArticleRow';
import { ManageEmpty, ManageError, ManageSkeleton } from './ManageStates';

/** 观察器只绑定右侧视口，刷新成功才回顶；刷新失败保留旧条目与滚动位置。 */
export function ManageArticleList({ list, enabled, paused = false, uncertainIds, onDelete }: {
  list: ReturnType<typeof useMyArticles>; enabled: boolean; paused?: boolean; uncertainIds?: ReadonlySet<string>;
  onDelete: (article: ArticleCardData) => void;
}) {
  const root = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const seconds = useCooldown(list.retryAt);
  useLayoutEffect(() => { if (root.current) root.current.scrollTop = 0; }, [list.revision]);
  useEffect(() => {
    if (!root.current || !sentinel.current || !enabled || paused || list.status !== 'ready' || list.paused
      || list.nextCursor === null || !window.IntersectionObserver) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void list.loadMore();
    }, { root: root.current, rootMargin: '0px 0px 200px 0px' });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [enabled, paused, list.status, list.paused, list.nextCursor, list.items.length, list.loadMore]);

  const failed = ['appendError', 'resetRequired'].includes(list.status);
  const restart = () => { void list.refresh(); };
  return <div className="manage-list" ref={root} role="region" tabIndex={0} aria-label="已发布文章列表"
    aria-busy={['initialLoading', 'loadingMore', 'refreshing'].includes(list.status)}>
    {list.items.map((item) => <ManageArticleRow key={item.articleId} article={item} disabled={!enabled || paused || list.status === 'refreshing'}
      uncertain={uncertainIds?.has(item.articleId)} onDelete={onDelete} />)}
    {list.status === 'initialLoading' && <ManageSkeleton />}
    {list.status === 'initialError' && <ManageError seconds={seconds} onRetry={() => { void list.retry(); }} />}
    {list.status === 'ready' && !list.items.length && list.nextCursor === null && <ManageEmpty />}
    {!['initialLoading', 'initialError'].includes(list.status) && !(list.status === 'ready' && !list.items.length && list.nextCursor === null) && <div ref={sentinel} className="manage-tail" aria-live="polite">
      {list.status === 'loadingMore' && <span className="loading-label"><i className="spinner" />正在加载更多内容</span>}
      {failed && <button className="manage-tail-action" disabled={!enabled || seconds > 0}
        onClick={list.status === 'appendError' ? () => { void list.retry(); } : restart}>{seconds > 0 ? `${seconds}秒后重试` : '加载失败，点击重试'}</button>}
      {list.status === 'ready' && list.nextCursor === null && list.items.length > 0 && <p>没有更多内容了</p>}
      {list.status === 'ready' && list.nextCursor !== null && (list.paused || !window.IntersectionObserver) && <>
        <button className="manage-tail-action" disabled={!enabled || paused} onClick={() => { void list.loadMore(true); }}>继续加载</button></>}
    </div>}
  </div>;
}
