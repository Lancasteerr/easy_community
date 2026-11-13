import type { RefObject } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ArticleDetailData } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../components/Icon';
import { useToast } from '../components/Toast';
import { useCooldown } from '../hooks/useCooldown';
import { capturePosition, type ReadingGeometry } from '../navigation/useScrollMemory';
import { useArticleLike } from './useArticleLike';

/** 按设计稿向下截取一位小数，完整值始终保留在提示和无障碍名称中。 */
export function compactLikes(value: number) {
  const unit = value >= 1_000_000_000 ? 1_000_000_000 : value >= 1_000_000 ? 1_000_000 : value >= 1000 ? 1000 : 1;
  const suffix = unit === 1_000_000_000 ? 'b' : unit === 1_000_000 ? 'm' : unit === 1000 ? 'k' : '';
  return unit === 1 ? String(value) : `${Math.floor(value / unit * 10) / 10}${suffix}`;
}

/** 左侧操作独立于正文；登录回跳只携带阅读位置，不携带待重放命令。 */
export function ArticleActions({ article, reader, geometry }: {
  article: ArticleDetailData; reader: RefObject<HTMLElement | null>; geometry?: ReadingGeometry;
}) {
  const auth = useAuth();
  const navigate = useNavigate();
  const notify = useToast();
  const login = () => navigate('/login', { state: { returnTo: `/articles/${article.articleId}`, readingPosition: capturePosition(reader.current, geometry) } });
  const like = useArticleLike(article, { enabled: auth.status === 'authenticated' && !auth.refreshing && !auth.error,
    userId: auth.user?.userId, notify, onUnauthorized: () => { auth.expireSession(); login(); } });
  const seconds = useCooldown(like.retryAt);
  const blocked = auth.status === 'loading' || auth.status === 'error' || auth.refreshing || !!auth.error;
  const label = `${like.liked ? '取消点赞' : '点赞'} ${new Intl.NumberFormat('en-US').format(like.count)}`;
  return <aside className="detail-actions" aria-label="文章操作">
    <div><button aria-label={label} title={seconds ? `${seconds}秒后重试` : label} aria-pressed={like.liked}
      aria-busy={like.busy} disabled={blocked || like.busy || seconds > 0}
      onClick={() => { if (auth.status === 'guest') login(); else void like.toggle(); }}><Icon name="like" size={24} /></button>
      <span title={String(like.count)}>{compactLikes(like.count)}</span></div>
    <div><button aria-label="评论暂未开放" onClick={() => notify('评论暂未开放')}><Icon name="comment" size={24} /></button><span>—</span></div>
  </aside>;
}
