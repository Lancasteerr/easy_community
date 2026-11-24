import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { getReplies, setCommentLike, type CommentItem, type CommentPage } from '../../api/comments';
import { useAuth } from '../../auth/AuthContext';
import { Avatar } from '../../components/Avatar';
import { Icon } from '../../components/Icon';
import { useToast } from '../../components/Toast';
import { useCooldown } from '../../hooks/useCooldown';
import { useArticleLike } from '../useArticleLike';

interface ThreadProps {
  articleId: string;
  authorId: string;
  root: CommentItem;
  onLogin: () => void;
}

const relativeTime = new Intl.RelativeTimeFormat('zh-CN', { numeric: 'auto' });
const timeUnits = [[86_400, 'day'], [3_600, 'hour'], [60, 'minute']] as const;

/** 仅展示相对时间，完整时间仍可查看。 */
function CommentTime({ value }: { value: string }) {
  const date = new Date(value);
  const seconds = Math.max(0, (Date.now() - date.getTime()) / 1000);
  const unit = timeUnits.find(([size]) => seconds >= size);
  return <time dateTime={value} title={date.toLocaleString()}>
    {unit ? relativeTime.format(-Math.floor(seconds / unit[0]), unit[1]) : '刚刚'}
  </time>;
}

function Name({ item, authorId }: { item: CommentItem; authorId: string }) {
  return <div className="comment-name"><strong>{item.author.username}</strong>
    {item.author.id === authorId && <span className="comment-author">作者</span>}</div>;
}

function CommentLike({ articleId, root, onLogin }: Pick<ThreadProps, 'articleId' | 'root' | 'onLogin'>) {
  const auth = useAuth();
  const notify = useToast();
  const blocked = auth.refreshing || !!auth.error || (auth.status !== 'guest' && auth.status !== 'authenticated');
  const like = useArticleLike({ articleId: root.id, likeCount: root.likeCount ?? 0, likedByMe: root.likedByMe ?? false }, {
    enabled: !blocked && auth.status === 'authenticated', userId: auth.identity?.userId,
    notify, onUnauthorized: auth.expireSession,
    write: (id, liked, operation, csrf, signal) => setCommentLike(articleId, id, liked, operation, csrf, signal),
  });
  const cooldown = useCooldown(like.retryAt);
  const label = `${like.liked ? '取消点赞评论' : '点赞评论'} ${like.count}`;
  return <button type="button" className="comment-action comment-like" aria-label={label} title={cooldown ? `${cooldown}秒后重试` : label}
    aria-pressed={like.liked} aria-busy={like.busy} disabled={blocked || like.busy || cooldown > 0}
    onClick={() => { if (auth.status === 'guest') onLogin(); else void like.toggle(); }}>
    <Icon name="like" size={16} />{like.count}
  </button>;
}

/** 在预览游标之后继续读取，回复始终平铺在同一楼层。 */
export function CommentThread({ articleId, authorId, root, onLogin }: ThreadProps) {
  const [page, setPage] = useState<CommentPage | undefined>(root.replies);
  const [loading, setLoading] = useState(false);
  const [failure, setFailure] = useState<{ cursor: string | null } | null>(null);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => { pending.current?.abort(); pending.current = null; }, []);

  async function load(cursor: string | null) {
    if (pending.current) return;
    const controller = new AbortController();
    pending.current = controller;
    setLoading(true);
    setFailure(null);
    try {
      const next = await getReplies(articleId, root.id, cursor, controller.signal);
      if (controller.signal.aborted) return;
      setPage((previous) => {
        if (cursor === null || !previous) return next;
        const shown = new Set(previous.items.map((item) => item.id));
        return { ...next, items: [...previous.items, ...next.items.filter((item) => !shown.has(item.id))] };
      });
    } catch (error) {
      if (!controller.signal.aborted) {
        setFailure({ cursor: error instanceof ApiError && error.code === 'COMMENT_CURSOR_EXPIRED' ? null : cursor });
      }
    } finally {
      if (pending.current === controller) {
        pending.current = null;
        setLoading(false);
      }
    }
  }

  return <li className="comment-thread">
    <div className="comment-avatar" aria-hidden="true">
      {root.author.avatarUrl ? <Avatar src={root.author.avatarUrl} size={40} /> : Array.from(root.author.username)[0]}
    </div>
    <div className="comment-thread-body">
      <Name item={root} authorId={authorId} />
      <p className="comment-content">{root.content}</p>
      <div className="comment-meta"><CommentTime value={root.createdAt} />
        <CommentLike articleId={articleId} root={root} onLogin={onLogin} /></div>
      {(page?.items.length || page?.hasMore || loading || failure) ? <div className="comment-replies">
        {page?.items.length ? <ul className="comment-list">
          {page.items.map((item) => <li key={item.id} className="comment-reply">
            <Name item={item} authorId={authorId} />
            <p className="comment-content">{item.content}</p>
            <div className="comment-meta"><CommentTime value={item.createdAt} /></div>
          </li>)}
        </ul> : null}
        {loading && <p className="comment-status" role="status">正在加载回复…</p>}
        {failure && <button type="button" className="comment-action" onClick={() => void load(failure.cursor)}>重新加载回复</button>}
        {!loading && !failure && page?.hasMore
          && <button type="button" className="comment-action" onClick={() => void load(page.nextCursor)}>展开更多回复 ›</button>}
      </div> : null}
    </div>
  </li>;
}
