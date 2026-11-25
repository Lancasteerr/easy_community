import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import { getReplies, setCommentLike, type CommentItem, type CommentPage } from '../../api/comments';
import { useAuth } from '../../auth/AuthContext';
import { Avatar } from '../../components/Avatar';
import { Icon } from '../../components/Icon';
import { useToast } from '../../components/Toast';
import { useCooldown } from '../../hooks/useCooldown';
import { useArticleLike } from '../useArticleLike';
import { CommentContent } from './CommentContent';
import { mergePublishedComments, visibleReplies } from './usePublishedComments';

interface ThreadProps {
  articleId: string;
  authorId: string;
  root: CommentItem;
  onLogin: () => void;
  onReply: (item: CommentItem) => void;
  canReply: boolean;
  editingId?: string;
  publishedReplies?: readonly CommentItem[];
  reconcilePublishedReplies: (rootId: string, official: readonly CommentItem[]) => void;
  editor: ReactNode;
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
    <AuthorBadge item={item} authorId={authorId} /></div>;
}

function AuthorBadge({ item, authorId }: { item: CommentItem; authorId: string }) {
  return item.author.id === authorId ? <span className="comment-author">作者</span> : null;
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

/** 临时回复排在楼层顶部，分页仍使用服务端的预览及续读游标。 */
export function CommentThread({ articleId, authorId, root, onLogin, onReply, canReply, editingId,
  publishedReplies, reconcilePublishedReplies, editor }: ThreadProps) {
  const preview = root.replies;
  const [loaded, setLoaded] = useState<{ preview: CommentPage | undefined; page: CommentPage }>();
  // 列表重新取得楼层预览时，从该预览的游标重新开始。
  const page = loaded && loaded.preview === preview ? loaded.page : preview;
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [failure, setFailure] = useState<{ cursor: string | null; preview: CommentPage | undefined } | null>(null);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => { pending.current?.abort(); pending.current = null; }, []);

  const load = useCallback(async (cursor: string | null) => {
    if (pending.current) return;
    const controller = new AbortController();
    pending.current = controller;
    setLoading(true);
    setFailure(null);
    try {
      const next = await getReplies(articleId, root.id, cursor, controller.signal);
      if (controller.signal.aborted) return;
      setLoaded((previous) => {
        const existing = previous && previous.preview === preview ? previous.page : preview;
        if (cursor === null || !existing) return { preview, page: next };
        const shown = new Set(existing.items.map((item) => item.id));
        return { preview, page: { ...next, items: [...existing.items, ...next.items.filter((item) => !shown.has(item.id))] } };
      });
    } catch (error) {
      if (!controller.signal.aborted) {
        const expired = error instanceof ApiError && error.code === 'COMMENT_CURSOR_EXPIRED';
        const missing = error instanceof ApiError && error.status === 404;
        setFailure({ cursor: expired || missing ? null : cursor, preview });
      }
    } finally {
      if (pending.current === controller) {
        pending.current = null;
        setLoading(false);
      }
    }
  }, [articleId, root.id, preview]);

  const officialReplies = page?.items;
  useEffect(() => {
    if (publishedReplies?.length && officialReplies) reconcilePublishedReplies(root.id, officialReplies);
  }, [officialReplies, publishedReplies, reconcilePublishedReplies, root.id]);

  const replies = mergePublishedComments(publishedReplies, officialReplies);
  const visible = visibleReplies(replies, expanded);
  const buffered = replies.length > visible.length;
  const canExpand = buffered || page?.hasMore;
  const currentFailure = failure && failure.preview === preview ? failure : null;
  const replyButton = (item: CommentItem) => <button type="button" className="comment-action"
    disabled={!canReply} aria-expanded={editingId === item.id} onClick={() => onReply(item)}>回复</button>;

  return <li className="comment-thread">
    <div className="comment-avatar" aria-hidden="true">
      {root.author.avatarUrl ? <Avatar src={root.author.avatarUrl} size={40} /> : Array.from(root.author.username)[0]}
    </div>
    <div className="comment-thread-body">
      <Name item={root} authorId={authorId} />
      <CommentContent content={root.content} maxLines={10} />
      <div className="comment-meta"><CommentTime value={root.createdAt} />
        {replyButton(root)}
        <CommentLike articleId={articleId} root={root} onLogin={onLogin} /></div>
      {editingId === root.id && editor}
      {(replies.length || page?.hasMore || loading || currentFailure) ? <div className="comment-replies">
        {visible.length ? <ul className="comment-list">
          {visible.map((item) => <li key={item.id} className="comment-reply">
            <CommentContent content={item.content} maxLines={3} prefix={<>
              <strong>{item.author.username}</strong><AuthorBadge item={item} authorId={authorId} />
              {'：'}{item.replyToUsername && <>回复<strong>{item.replyToUsername}</strong>：</>}
            </>} />
            <div className="comment-meta"><CommentTime value={item.createdAt} />{replyButton(item)}</div>
            {editingId === item.id && editor}
          </li>)}
        </ul> : null}
        {loading && <p className="comment-status" role="status">正在加载回复…</p>}
        {currentFailure && <button type="button" className="comment-action" onClick={() => void load(currentFailure.cursor)}>重新加载回复</button>}
        {!loading && !currentFailure && canExpand
          && <button type="button" className="comment-action" onClick={() => {
            setExpanded(true);
            if (!buffered) void load(page?.nextCursor ?? null);
          }}>展开更多回复 ›</button>}
      </div> : null}
    </div>
  </li>;
}
