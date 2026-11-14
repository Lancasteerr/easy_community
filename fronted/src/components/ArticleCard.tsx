import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { ArticleCardData } from '../api/types';
import { Avatar } from './Avatar';
import { Icon } from './Icon';
import { useUnavailable } from './Toast';
import { ArticleCardLikeButton } from './ArticleCardLikeButton';
import { profilePath } from '../profile/routes';

const number = new Intl.NumberFormat('en-US');

/** 相对时间仅用于展示，不改写后端原始时间或参与游标计算。 */
function relativeTime(value: string) {
  const seconds = Math.max(0, Math.floor((Date.now() - Date.parse(value)) / 1000));
  if (!Number.isFinite(seconds)) return '';
  if (seconds < 60) return '刚刚';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}小时前`;
  if (seconds < 86400 * 30) return `${Math.floor(seconds / 86400)}天前`;
  return new Date(value).toLocaleDateString('zh-CN');
}

export function ArticleCard({ article, onLogin, context = 'feed' }: { article: ArticleCardData; onLogin: () => void; context?: 'feed' | 'profile' | 'search' }) {
  const unavailable = useUnavailable();
  const [brokenCover, setBrokenCover] = useState<string>();
  // 稳定的文章锚点用于返回首页后校正图片加载造成的阅读位置偏移。
  const metadata = <><time dateTime={article.publishedAt} title={new Date(article.publishedAt).toLocaleString('zh-CN')}>
    {relativeTime(article.publishedAt)}</time><span> · 阅读 {number.format(article.viewCount)}</span></>;
  // 搜索仅改变排版；封面失败时保留占位，阅读锚点不会随图片状态跳动。
  const variant = context === 'search' ? ` search-article-card${article.coverUrl ? ' has-cover' : ''}` : context === 'profile' ? ' profile-article-card' : '';
  return <article className={`article-card${variant}`} data-read-block={article.articleId} aria-labelledby={`article-${article.articleId}`}>
    {context === 'profile' ? <div className="article-meta">{metadata}</div> : <div className="article-author-row">
      <Link className="author-entry" aria-label={`查看作者 ${article.authorName}`} to={profilePath(article.authorId)}>
        <Avatar src={article.authorAvatarUrl} /><span className="author-details"><span className="author-name">{article.authorName}</span>
          <span className="article-meta">{metadata}</span>
        </span>
      </Link>
      <button className="follow-button" onClick={unavailable}>+ 关注</button>
    </div>}
    <div className="article-copy">
      <h2 id={`article-${article.articleId}`}><Link to={`/articles/${article.articleId}`}>{article.title}</Link></h2>
      <p>{article.summary}</p>
    </div>
    {article.coverUrl && <Link className="article-cover" to={`/articles/${article.articleId}`} aria-label={`阅读 ${article.title}`}>
      {brokenCover === article.coverUrl ? <span className="image-placeholder">封面暂时无法显示</span>
        : <img src={article.coverUrl} alt="" loading="lazy" onError={() => setBrokenCover(article.coverUrl!)} />}
    </Link>}
    <div className="article-interactions">
      <ArticleCardLikeButton article={article} onLogin={onLogin} />
      <button className="interaction-button" aria-label="评论暂未开放" onClick={unavailable}><Icon name="comment" /><span>—</span></button>
    </div>
  </article>;
}
