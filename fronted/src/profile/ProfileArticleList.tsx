import { useEffect, useRef, type RefObject } from 'react';
import { Link } from 'react-router-dom';
import { ArticleCard } from '../components/ArticleCard';
import { useCooldown } from '../hooks/useCooldown';
import type { useProfileArticles } from './useProfileArticles';
import { profileSamples } from './ProfileHeader';

/** 列表只有续页哨兵，滚动根始终是包含资料和信息卡的整页容器。 */
export function ProfileArticleList({ list, scroll, self, canPublish, onLogin }: {
  list: ReturnType<typeof useProfileArticles>; scroll: RefObject<HTMLDivElement | null>;
  self: boolean; canPublish: boolean; onLogin: () => void;
}) {
  const sentinel = useRef<HTMLDivElement>(null);
  const seconds = useCooldown(list.retryAt);
  useEffect(() => {
    if (!scroll.current || !sentinel.current || list.status !== 'ready' || list.paused || list.nextCursor === null || !window.IntersectionObserver) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void list.loadMore();
    }, { root: scroll.current, rootMargin: '0px 0px 600px 0px' });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [scroll, list.status, list.paused, list.nextCursor, list.items.length, list.loadMore]);
  const loading = ['initialLoading', 'loadingMore', 'refreshing'].includes(list.status);
  const reset = ['resetRequired', 'refreshError'].includes(list.status);
  const failed = reset || ['initialError', 'appendError'].includes(list.status);
  const empty = list.status === 'ready' && !list.items.length && list.nextCursor === null;
  return <section className="profile-articles glass-panel" aria-label="发布文章" aria-busy={loading}>
    <div className="profile-list-heading"><h2><span>发布</span><small>{profileSamples.articles}</small></h2><span>最新发布</span></div>
    {list.items.map((article) => <ArticleCard key={article.articleId} article={article} context="profile" onLogin={onLogin} />)}
    {list.status === 'initialLoading' && <div className="feed-skeleton" role="status" aria-label="正在加载发布文章">
      <div className="skeleton-title" /><div className="skeleton-summary" /><div className="skeleton-cover" /></div>}
    {empty && <div className="profile-empty"><h3>还没有发布文章</h3>
      <p>{self ? '发布第一篇文章，开始记录你的想法。' : '该用户还没有发布任何文章。'}</p>
      {self && (canPublish ? <Link className="profile-primary" to="/articles/new">去发布</Link> : <button className="profile-primary" disabled>去发布</button>)}
    </div>}
    {!empty && <div className="profile-list-status" ref={sentinel} aria-live="polite">
      {loading && list.status !== 'initialLoading' && <span>正在加载…</span>}
      {failed && <><p>{reset ? '列表需要重新加载' : '加载失败，请稍后重试'}</p>
        <button className="feed-action" disabled={seconds > 0} onClick={() => { void (reset ? list.restart() : list.retry()); }}>
          {seconds ? `${seconds}秒后重试` : reset ? '重新加载' : '加载失败，点击重试'}</button></>}
      {list.status === 'ready' && list.nextCursor === null && list.items.length > 0 && <span>没有更多内容了</span>}
      {list.status === 'ready' && list.nextCursor !== null && (list.paused || !window.IntersectionObserver) &&
        <button className="feed-action" onClick={() => { void list.loadMore(true); }}>继续加载</button>}
    </div>}
  </section>;
}
