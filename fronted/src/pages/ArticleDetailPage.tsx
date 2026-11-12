import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useArticleDetail } from '../article/useArticleDetail';
import { useCooldown } from '../hooks/useCooldown';

/** 详情读取不依赖登录初始化，公开文章允许游客直接阅读。 */
export function ArticleDetailPage() {
  const { id } = useParams();
  return <DetailSession key={id} id={id} />;
}

function DetailSession({ id }: { id: string | undefined }) {
  const detail = useArticleDetail(id);
  const seconds = useCooldown(detail.retryAt);
  useEffect(() => { document.title = `${detail.article?.title ?? '文章详情'} · Easy Community`; }, [detail.article?.title]);
  return <main className="feed-status" aria-label="文章详情" aria-busy={detail.status === 'loading'}>
    {detail.status === 'loading' && <div className="feed-skeleton" role="status" aria-label="正在加载文章">
      <div className="skeleton-title" /><div className="skeleton-cover" /><div className="skeleton-summary" />
    </div>}
    {detail.status === 'missing' && <><h1>文章不存在或已删除</h1><Link to="/">返回首页</Link></>}
    {detail.status === 'error' && <><p>{errorMessage(detail.error)}</p><button className="feed-action" disabled={seconds > 0}
      onClick={() => void detail.retry()}>{seconds ? `${seconds}秒后重试` : '重新加载'}</button></>}
    {detail.article && <article><h1>{detail.article.title}</h1>
      <div dangerouslySetInnerHTML={{ __html: detail.article.contentHtml }} /></article>}
  </main>;
}
