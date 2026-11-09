import { useEffect, useRef } from 'react';
import { errorMessage } from '../api/client';
import { useFeed } from '../hooks/useFeed';
import { useCooldown } from '../hooks/useCooldown';
import { ArticleCard } from './ArticleCard';

/** 观察器绑定窗口滚动；每次游标变化重建，以支持短列表自动填充首屏。 */
export function Feed() {
  const feed = useFeed();
  const sentinel = useRef<HTMLDivElement>(null);
  const seconds = useCooldown(feed.retryAt);
  useEffect(() => {
    if (!sentinel.current || feed.status !== 'ready' || feed.paused || feed.nextCursor === null || !window.IntersectionObserver) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void feed.loadMore();
    }, { rootMargin: '600px 0px' });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [feed.status, feed.nextCursor, feed.paused, feed.loadMore]);

  const restart = () => {
    window.scrollTo({ top: 0, behavior: 'instant' });
    feed.restart();
  };
  return <section className="feed-list" aria-label="推荐文章" aria-busy={feed.status === 'loading'}>
    {feed.items.map((article) => <ArticleCard key={article.articleId} article={article} />)}
    {feed.status === 'loading' && !feed.items.length && <div className="feed-skeleton" role="status" aria-label="正在加载推荐文章">
      <div className="skeleton-author"><i /><span /></div><div className="skeleton-title" /><div className="skeleton-summary" /><div className="skeleton-cover" />
    </div>}
    <div ref={sentinel} className="feed-status" aria-live="polite">
      {feed.status === 'loading' && feed.items.length > 0 && <span className="loading-label"><i className="spinner" />正在加载更多…</span>}
      {feed.status === 'error' && <><p>{errorMessage(feed.error)}</p><button className="feed-action" disabled={seconds > 0}
        onClick={() => void feed.loadMore(true)}>{seconds ? `${seconds}秒后重试` : '重试'}</button></>}
      {feed.status === 'expired' && <><p>列表已过期，已读内容仍保留</p><button className="feed-action" onClick={restart}>重新加载</button></>}
      {feed.status === 'ready' && feed.nextCursor === null && <p>{feed.items.length ? '已经到底了' : '还没有推荐内容，稍后再来看看吧'}</p>}
      {feed.status === 'ready' && feed.nextCursor !== null && <>
        {feed.paused && <p>暂未发现更多内容，可点击继续加载</p>}
        <button className="feed-action" onClick={() => void feed.loadMore(true)}>加载更多</button>
      </>}
    </div>
  </section>;
}
