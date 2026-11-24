import { useState, type RefObject } from 'react';
import type { CommentSort } from '../../api/comments';
import { useAuth } from '../../auth/AuthContext';
import { CommentThread } from './CommentThread';
import { useComments } from './useComments';
import './comments.css';

interface CommentSectionProps {
  articleId: string;
  authorId: string;
  count: number;
  anchor: RefObject<HTMLElement | null>;
  onCount: (count: number) => void;
  onLogin: () => void;
}

/** 评论卡片先挂载定位锚点，列表请求独立于文章阅读。 */
export function CommentSection({ articleId, authorId, count, anchor, onCount, onLogin }: CommentSectionProps) {
  const [sort, setSort] = useState<CommentSort>('hot');
  const { page, loading, failure, load } = useComments(articleId, sort, onCount);
  const auth = useAuth();
  return <section id="comments" className="article-comments" ref={anchor} tabIndex={-1} aria-labelledby="comments-heading">
    {auth.status === 'guest' && <div className="comment-login">
      <span>登录后参与讨论</span><button type="button" className="comment-primary" onClick={onLogin}>登录</button>
    </div>}
    <div className="comment-list-header">
      <h2 id="comments-heading">全部评论 <span>{count.toLocaleString()}</span></h2>
      <div className="comment-sort" role="group" aria-label="评论排序">
        <button type="button" aria-pressed={sort === 'hot'} onClick={() => setSort('hot')}>最热</button>
        <button type="button" aria-pressed={sort === 'latest'} onClick={() => setSort('latest')}>最新</button>
      </div>
    </div>
    {page?.items.length ? <ul className="comment-list" aria-label="评论列表">
      {page.items.map((root) => <CommentThread key={root.id} articleId={articleId} authorId={authorId} root={root} onLogin={onLogin} />)}
    </ul> : null}
    {loading && <p className="comment-status" role="status">正在加载评论…</p>}
    {failure && <div className="comment-status">
      <span>暂时无法加载评论</span>
      <button type="button" className="comment-action" onClick={() => void load(failure.cursor)}>重新加载</button>
    </div>}
    {!loading && !failure && page && <div className="comment-status">
      {page.hasMore ? <button type="button" className="comment-action" onClick={() => void load(page.nextCursor)}>加载更多评论</button>
        : page.items.length ? '已显示全部评论' : count ? '暂未加载到评论' : '还没有评论，来分享你的想法吧'}
    </div>}
  </section>;
}
