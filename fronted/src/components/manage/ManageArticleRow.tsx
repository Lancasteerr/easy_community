import { useState } from 'react';
import type { ArticleCardData } from '../../api/types';
import { Icon } from '../Icon';
import { useUnavailable } from '../Toast';

/** 管理列表保持绝对发布时间；不将展示时间用于排序或游标计算。 */
export function publicationTime(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '—';
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function compactCount(value: number) {
  const unit = value >= 100_000_000 ? 100_000_000 : value >= 10_000 ? 10_000 : 1;
  return unit === 1 ? String(value) : `${(value / unit).toFixed(1).replace(/\.0$/, '')}${unit === 10_000 ? '万' : '亿'}`;
}

/** 指标只读，真实零值与尚未开放的能力分开展示。 */
export function ManageArticleRow({ article, disabled = false, uncertain = false, onDelete }: {
  article: ArticleCardData; disabled?: boolean; uncertain?: boolean; onDelete: (article: ArticleCardData) => void;
}) {
  const unavailable = useUnavailable();
  const [brokenCover, setBrokenCover] = useState<string>();
  const coverFailed = !!article.coverUrl && brokenCover === article.coverUrl;
  return <article className="manage-row" aria-labelledby={`manage-title-${article.articleId}`}>
    <button className="manage-cover" onClick={unavailable} aria-label={`查看文章 ${article.title}`}>
      {article.coverUrl && !coverFailed
        ? <img src={article.coverUrl} alt="" loading="lazy" onError={() => setBrokenCover(article.coverUrl!)} />
        : <span className="manage-cover-placeholder"><Icon name="document" size={36} /><span>暂无封面</span></span>}
    </button>
    <div className="manage-copy">
      <h2 id={`manage-title-${article.articleId}`}><button title={article.title} onClick={unavailable}>{article.title}</button></h2>
      <time dateTime={article.publishedAt}>{publicationTime(article.publishedAt)}</time>
      <div className="manage-metrics">
        <span title={`阅读 ${article.viewCount}`} aria-label={`阅读 ${article.viewCount}`}><Icon name="manageView" size={16} />{compactCount(article.viewCount)}</span>
        <span title="评论暂未开放" aria-label="评论暂未开放"><Icon name="manageComment" size={16} />—</span>
        <span title={`点赞 ${article.likeCount}`} aria-label={`点赞 ${article.likeCount}`}><Icon name="manageLike" size={16} />{compactCount(article.likeCount)}</span>
        <span title="收藏暂未开放" aria-label="收藏暂未开放"><Icon name="favorite" size={16} />—</span>
      </div>
    </div>
    <div className="manage-row-actions">
      <button className="manage-edit" onClick={unavailable}>编辑</button>
      <button className="manage-delete" data-delete-id={article.articleId} disabled={disabled}
        aria-label={`${uncertain ? '核对删除结果' : '删除'} ${article.title}`} onClick={() => onDelete(article)}>{uncertain ? '核对' : '删除'}</button>
    </div>
  </article>;
}
