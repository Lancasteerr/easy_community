import { useEffect, useMemo, useRef } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import type { ArticleDetailData } from '../api/types';
import { useArticleDetail } from '../article/useArticleDetail';
import { useCooldown } from '../hooks/useCooldown';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { Avatar } from '../components/Avatar';
import { Icon } from '../components/Icon';
import { useToast, useUnavailable } from '../components/Toast';
import { ArticleHero } from '../article/ArticleHero';
import { ArticleBody } from '../article/ArticleBody';
import { ArticleToc } from '../article/ArticleToc';
import { prepareContent } from '../article/content';
import { useArticleToc } from '../article/useArticleToc';
import { useScrollMemory, type ReadingPosition } from '../navigation/useScrollMemory';
import './detail.css';

/** 详情读取不依赖登录初始化，公开文章允许游客直接阅读。 */
export function ArticleDetailPage() {
  const { id } = useParams();
  return <DetailSession key={id} id={id} />;
}

function DetailSession({ id }: { id: string | undefined }) {
  const detail = useArticleDetail(id);
  const seconds = useCooldown(detail.retryAt);
  const auth = useAuth();
  const navigate = useNavigate();
  const notify = useToast();
  useEffect(() => { document.title = `${detail.article?.title ?? '文章详情'} · Easy Community`; }, [detail.article?.title]);
  useEffect(() => { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); }, []);
  const avatarClick = () => {
    if (auth.status === 'guest') navigate('/login', { state: { returnTo: `/articles/${id}` } });
    else if (auth.status === 'authenticated') notify('个人中心暂未开放');
    else { notify('正在确认登录状态，请稍后重试'); void auth.refresh(); }
  };
  return <div className="detail-page">
    <Header avatarUrl={auth.user?.avatarUrl} onAvatarClick={avatarClick}
      avatarLabel={auth.user ? `${auth.user.username} 的个人中心` : auth.status === 'guest' ? '登录' : '重新检查登录状态'} />
    {detail.article ? <ArticleReader article={detail.article} /> : <main className="detail-columns" aria-label="文章详情" aria-busy={detail.status === 'loading'}>
      <div className="detail-reader detail-state">
        {detail.status === 'loading' && <div className="feed-skeleton" role="status" aria-label="正在加载文章">
          <div className="skeleton-title" /><div className="skeleton-cover" /><div className="skeleton-summary" />
        </div>}
        {detail.status === 'missing' && <div><h1>文章不存在或已删除</h1><Link to="/">返回首页</Link></div>}
        {detail.status === 'error' && <div><p>{errorMessage(detail.error)}</p><button className="feed-action" disabled={seconds > 0}
          onClick={() => void detail.retry()}>{seconds ? `${seconds}秒后重试` : '重新加载'}</button></div>}
      </div>
    </main>}
  </div>;
}

/** 固定区域均位于滚动容器之外，正文和封面只组成一条自然内容流。 */
function ArticleReader({ article }: { article: ArticleDetailData }) {
  const root = useRef<HTMLElement>(null);
  const location = useLocation();
  const auth = useAuth();
  const notify = useToast();
  const unavailable = useUnavailable();
  const content = useMemo(() => prepareContent(article.contentHtml), [article.contentHtml]);
  const toc = useArticleToc(root, content.entries);
  useScrollMemory(root, `detail:${location.key}`, true, location.state?.readingPosition as ReadingPosition | undefined);
  return <main className="detail-columns" aria-label="文章详情">
    <aside className="detail-actions" aria-label="文章操作">
      <div><button aria-label={`点赞 ${article.likeCount}`} aria-pressed={article.likedByMe} onClick={unavailable}><Icon name="like" size={24} /></button><span>{article.likeCount}</span></div>
      <div><button aria-label="评论暂未开放" onClick={() => notify('评论暂未开放')}><Icon name="comment" size={24} /></button><span>—</span></div>
    </aside>
    <section className="detail-reader" ref={root} tabIndex={0} aria-label="文章阅读区域">
      <article className="detail-flow"><ArticleHero article={article} /><ArticleBody content={content} /></article>
    </section>
    <aside className="detail-sidebar" aria-label="作者与目录">
      <section className="detail-author glass-panel" aria-label="文章作者">
        <div className="detail-author-row"><button className="author-entry" aria-label={`查看作者 ${article.authorName}`} onClick={unavailable}>
          <Avatar src={article.authorAvatarUrl} size={60} /><span className="detail-author-name" title={article.authorName}>{article.authorName}</span>
        </button>{auth.user?.userId !== article.authorId && <button className="follow-button" onClick={unavailable}>+ 关注</button>}</div>
        <p>作者简介暂未开放</p>
      </section>
      <ArticleToc entries={content.entries} active={toc.active} onJump={toc.jump} />
    </aside>
  </main>;
}
