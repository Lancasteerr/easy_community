import { useEffect, useRef, type RefObject } from 'react';
import { ApiError, errorMessage } from '../api/client';
import { ArticleCard } from '../components/ArticleCard';
import { useCooldown } from '../hooks/useCooldown';
import { useScrollMemory } from '../navigation/useScrollMemory';
import type { SearchSort } from './routes';
import { useArticleSearch } from './useArticleSearch';

/** 列表只放续页哨兵；加载和位置恢复都使用包含导航及侧栏的整页滚动根。 */
export function SearchResults({ q, sort, historyKey, scroll, onLogin }: {
  q: string; sort: SearchSort; historyKey: string;
  scroll: RefObject<HTMLDivElement | null>; onLogin: () => void;
}) {
  const snapshotKey = `search:${historyKey}:${sort}:${q}`;
  const list = useArticleSearch(q, sort, snapshotKey);
  const content = useRef<HTMLElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const seconds = useCooldown(list.retryAt);
  const stopRestoring = useScrollMemory(scroll, `${snapshotKey}:scroll`, list.items.length > 0 || list.status !== 'loading',
    undefined, undefined, 0, { contentRef: content, topInset: 296 });

  useEffect(() => {
    if (!scroll.current || !sentinel.current || list.status !== 'ready' || list.paused || list.nextCursor === null || !window.IntersectionObserver) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void list.loadMore();
    }, { root: scroll.current, rootMargin: '0px 0px 600px 0px' });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [scroll, list.status, list.paused, list.nextCursor, list.loadMore]);

  const restart = () => {
    // 先保存回顶位置，再替换首屏，避免恢复逻辑取回已失效列表的旧锚点。
    if (scroll.current) scroll.current.scrollTop = 0;
    stopRestoring(); list.restart();
  };
  const empty = list.status === 'ready' && !list.items.length && list.nextCursor === null;
  const expired = list.error instanceof ApiError && list.error.code === 'ARTICLE_CURSOR_EXPIRED';
  return <section className="search-results" ref={content} aria-label="搜索结果" aria-busy={list.status === 'loading'}>
    {list.items.map((article) => <ArticleCard key={article.articleId} article={article} context="search" onLogin={onLogin} />)}
    {list.status === 'loading' && !list.items.length && <div className="feed-skeleton search-skeleton" role="status" aria-label="正在搜索文章">
      <div className="skeleton-author"><i /><span /></div><div className="skeleton-title" /><div className="skeleton-summary" /><div className="skeleton-cover" />
    </div>}
    {empty && <div className="search-empty"><h2>暂未找到相关文章</h2><p>试试更简短的关键词，或换一种表达</p></div>}
    <div ref={sentinel} className="feed-status search-list-status" aria-live="polite">
      {list.status === 'loading' && list.items.length > 0 && <span className="loading-label"><i className="spinner" />正在加载更多…</span>}
      {list.status === 'error' && <><p>{errorMessage(list.error)}</p><button className="feed-action" disabled={seconds > 0}
        onClick={() => void list.loadMore(true)}>{seconds ? `${seconds}秒后重试` : '重试'}</button></>}
      {list.status === 'resetRequired' && <><p>{expired ? '列表已过期，已读内容仍保留' : '列表需要重新加载，已读内容仍保留'}</p>
        <button className="feed-action" disabled={seconds > 0} onClick={restart}>{seconds ? `${seconds}秒后重试` : '重新加载'}</button></>}
      {list.status === 'ready' && list.items.length > 0 && list.nextCursor === null && <p>已展示全部搜索结果</p>}
      {list.status === 'ready' && list.nextCursor !== null && <>
        {list.paused && <p>暂未发现更多内容，可点击继续加载</p>}
        <button className="feed-action" onClick={() => void list.loadMore(true)}>加载更多</button>
      </>}
    </div>
  </section>;
}
