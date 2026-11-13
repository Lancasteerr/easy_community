import type { ArticleCardData } from '../api/types';
import { useArticleLike } from '../article/useArticleLike';
import { useAuth } from '../auth/AuthContext';
import { useCooldown } from '../hooks/useCooldown';
import { Icon } from './Icon';
import { useToast } from './Toast';

const number = new Intl.NumberFormat('en-US');

/** 卡片只负责点赞交互；登录回跳及阅读位置由推荐流统一管理。 */
export function ArticleCardLikeButton({ article, onLogin }: { article: ArticleCardData; onLogin: () => void }) {
  const auth = useAuth();
  const notify = useToast();
  const like = useArticleLike(article, { enabled: auth.status === 'authenticated' && !auth.refreshing && !auth.error,
    userId: auth.user?.userId, notify, onUnauthorized: () => { auth.expireSession(); onLogin(); } });
  const seconds = useCooldown(like.retryAt);
  const blocked = auth.status === 'loading' || auth.status === 'error' || auth.refreshing || !!auth.error;
  const label = `${like.liked ? '取消点赞' : '点赞'} ${number.format(like.count)}`;
  return <button className={`interaction-button${like.liked ? ' is-liked' : ''}`} aria-label={label}
    title={seconds ? `${seconds}秒后重试` : label} aria-pressed={like.liked} aria-busy={like.busy}
    disabled={blocked || like.busy || seconds > 0}
    onClick={() => { if (auth.status === 'guest') onLogin(); else void like.toggle(); }}>
    <Icon name="like" /><span>{number.format(like.count)}</span>
  </button>;
}
