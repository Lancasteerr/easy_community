import { useState } from 'react';
import type { ArticleDetailData } from '../api/types';

const numbers = new Intl.NumberFormat('en-US');

/** 封面为真实文章资源；标题区可随换行增高，缺图时以纯色保持可读性。 */
export function ArticleHero({ article }: { article: ArticleDetailData }) {
  const [failed, setFailed] = useState<string>();
  const cover = article.coverUrl && article.coverUrl !== failed ? article.coverUrl : null;
  return <header className={`detail-hero${cover ? ' has-cover' : ''}`}>
    {cover && <img className="detail-cover" src={cover} alt="" onError={() => setFailed(cover)} />}
    <div className="detail-title"><h1>{article.title}</h1><p>
      <span>更新于 <time dateTime={article.updatedAt} title={new Date(article.updatedAt).toLocaleString('zh-CN')}>
        {new Date(article.updatedAt).toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replaceAll('/', '-')}</time></span>
      <span>作者 {article.authorName}</span><span>阅读 {numbers.format(article.viewCount)}</span>
    </p></div>
  </header>;
}
