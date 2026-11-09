import { useState } from 'react';
import type { ArticleCardData } from '../api/types';
import { Avatar } from './Avatar';
import { Icon } from './Icon';
import { useUnavailable } from './Toast';

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

export function ArticleCard({ article }: { article: ArticleCardData }) {
  const unavailable = useUnavailable();
  const [brokenCover, setBrokenCover] = useState<string>();
  return <article className="article-card" aria-labelledby={`article-${article.articleId}`}>
    <div className="article-author-row">
      <button className="author-entry" aria-label={`查看作者 ${article.authorName}`} onClick={unavailable}>
        <Avatar /><span className="author-details"><span className="author-name">{article.authorName}</span>
          <span className="article-meta"><time dateTime={article.publishedAt} title={new Date(article.publishedAt).toLocaleString('zh-CN')}>
            {relativeTime(article.publishedAt)}</time><span> · 阅读 {number.format(article.viewCount)}</span></span>
        </span>
      </button>
      <button className="follow-button" onClick={unavailable}>+ 关注</button>
    </div>
    <div className="article-copy">
      <h2 id={`article-${article.articleId}`}><button onClick={unavailable}>{article.title}</button></h2>
      <p>{article.summary}</p>
    </div>
    {article.coverUrl && <button className="article-cover" onClick={unavailable} aria-label={`阅读 ${article.title}`}>
      {brokenCover === article.coverUrl ? <span className="image-placeholder">封面暂时无法显示</span>
        : <img src={article.coverUrl} alt="" loading="lazy" onError={() => setBrokenCover(article.coverUrl!)} />}
    </button>}
    <div className="article-interactions">
      <button className={`interaction-button${article.likedByMe ? ' is-liked' : ''}`} aria-pressed={article.likedByMe}
        aria-label={`点赞 ${number.format(article.likeCount)}`} onClick={unavailable}>
        <Icon name="like" /><span>{number.format(article.likeCount)}</span>
      </button>
      <button className="interaction-button" aria-label="评论暂未开放" onClick={unavailable}><Icon name="comment" /><span>—</span></button>
    </div>
  </article>;
}
