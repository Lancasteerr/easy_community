import { useEffect, useMemo, useRef, type RefObject } from 'react';
import { ApiError, errorMessage } from '../api/client';
import { useFeed } from '../hooks/useFeed';
import { useCooldown } from '../hooks/useCooldown';
import { ArticleCard } from './ArticleCard';
import { useLocation, useNavigate } from 'react-router-dom';
import { capturePosition, useScrollMemory } from '../navigation/useScrollMemory';
import { readingReturnState } from '../navigation/readingReturn';

/** 加载与位置记忆共用首页通栏容器；每次游标变化重建观察器，支持短列表补页。 */
export function Feed({ scrollContainer }: { scrollContainer: RefObject<HTMLDivElement | null> }) {
  const location = useLocation();
  const navigate = useNavigate();
  // 登录会创建新历史项，沿用原列表会话才能恢复已加载的深层卡片及续页游标。
  const returnState = useMemo(() => readingReturnState('/', location.state?.readingPosition, location.state?.feedSnapshotKey), [location.state]);
  const snapshotKey = returnState?.feedSnapshotKey ?? `feed:${location.key}`;
  const feed = useFeed(snapshotKey);
  const sentinel = useRef<HTMLDivElement>(null);
  const stopRestoring = useScrollMemory(scrollContainer, `feed-scroll:${location.key}`, feed.items.length > 0, returnState?.readingPosition);
  const login = () => navigate('/login', { state: { returnTo: '/', readingPosition: capturePosition(scrollContainer.current), feedSnapshotKey: snapshotKey } });
  const seconds = useCooldown(feed.retryAt);
  // 无效游标重试不会恢复；与过期游标一样，需要用户明确开始新一轮读取。
  const invalidCursor = feed.error instanceof ApiError && feed.error.code === 'ARTICLE_SEARCH_INVALID';
  useEffect(() => {
    if (!scrollContainer.current || !sentinel.current || feed.status !== 'ready' || feed.paused || feed.nextCursor === null || !window.IntersectionObserver) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void feed.loadMore();
    }, { root: scrollContainer.current, rootMargin: '600px 0px' });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [scrollContainer, feed.status, feed.nextCursor, feed.paused, feed.loadMore]);

  const restart = () => {
    // 主动重载终止旧位置校正，让横幅与文章一起回顶，保留窄屏横向位置。
    stopRestoring();
    scrollContainer.current?.scrollTo({ top: 0, behavior: 'instant' });
    feed.restart();
  };
  return <section className="feed-list" aria-label="推荐文章" aria-busy={feed.status === 'loading'}>
    {feed.items.map((article) => <ArticleCard key={article.articleId} article={article} onLogin={login} />)}
    {feed.status === 'loading' && !feed.items.length && <div className="feed-skeleton" role="status" aria-label="正在加载推荐文章">
      <div className="skeleton-author"><i /><span /></div><div className="skeleton-title" /><div className="skeleton-summary" /><div className="skeleton-cover" />
    </div>}
    <div ref={sentinel} className="feed-status" aria-live="polite">
      {feed.status === 'loading' && feed.items.length > 0 && <span className="loading-label"><i className="spinner" />正在加载更多…</span>}
      {feed.status === 'error' && <><p>{errorMessage(feed.error)}</p><button className="feed-action" disabled={seconds > 0}
        onClick={invalidCursor ? restart : () => void feed.loadMore(true)}>{seconds ? `${seconds}秒后重试` : invalidCursor ? '重新加载' : '重试'}</button></>}
      {feed.status === 'expired' && <><p>列表已过期，已读内容仍保留</p><button className="feed-action" onClick={restart}>重新加载</button></>}
      {feed.status === 'ready' && feed.nextCursor === null && <p>{feed.items.length ? '已经到底了' : '还没有推荐内容，稍后再来看看吧'}</p>}
      {feed.status === 'ready' && feed.nextCursor !== null && <>
        {feed.paused && <p>暂未发现更多内容，可点击继续加载</p>}
        <button className="feed-action" onClick={() => void feed.loadMore(true)}>加载更多</button>
      </>}
    </div>
  </section>;
}
