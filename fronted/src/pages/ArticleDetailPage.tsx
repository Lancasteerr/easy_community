import { useEffect, useMemo, useRef, type RefObject } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import type { ArticleDetailData } from '../api/types';
import { useArticleDetail } from '../article/useArticleDetail';
import { useCooldown } from '../hooks/useCooldown';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { Avatar } from '../components/Avatar';
import { useToast, useUnavailable } from '../components/Toast';
import { ArticleHero } from '../article/ArticleHero';
import { ArticleBody } from '../article/ArticleBody';
import { ArticleToc } from '../article/ArticleToc';
import { prepareContent } from '../article/content';
import { useArticleToc } from '../article/useArticleToc';
import { capturePosition, useScrollMemory, type ReadingPosition } from '../navigation/useScrollMemory';
import { ArticleActions } from '../article/ArticleActions';
import { useDetailViewport } from '../article/useDetailViewport';
import './detail.css';

/** 详情读取不依赖登录初始化，公开文章允许游客直接阅读。 */
export function ArticleDetailPage() {
  const { id } = useParams();
  return <DetailSession key={id} id={id} />;
}

function DetailSession({ id }: { id: string | undefined }) {
  const scroll = useRef<HTMLDivElement>(null);
  const top = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLElement>(null);
  const topInset = useDetailViewport(scroll, top);
  const geometry = { contentRef: body, topInset };
  const detail = useArticleDetail(id);
  const seconds = useCooldown(detail.retryAt);
  const auth = useAuth();
  const navigate = useNavigate();
  const notify = useToast();
  useEffect(() => { document.title = `${detail.article?.title ?? '文章详情'} · Easy Community`; }, [detail.article?.title]);
  useEffect(() => { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); }, []);
  const avatarClick = () => {
    if (auth.status === 'guest') navigate('/login', { state: { returnTo: `/articles/${id}`,
      readingPosition: capturePosition(scroll.current, geometry) } });
    else if (auth.status === 'authenticated') notify('个人中心暂未开放');
    else { notify('正在确认登录状态，请稍后重试'); void auth.refresh(); }
  };
  return <div className="detail-page">
    <div className="detail-scroll" ref={scroll} tabIndex={0} role="region" aria-label="文章详情滚动区域">
      <div className="detail-scroll-canvas">
        {/* 与主页保持相同的吸顶遮挡边界，卡片整体从导航背景下方经过。 */}
        <div className="detail-top-frame" ref={top}>
          <Header avatarUrl={auth.user?.avatarUrl} onAvatarClick={avatarClick}
            avatarLabel={auth.user ? `${auth.user.username} 的个人中心` : auth.status === 'guest' ? '登录' : '重新检查登录状态'} />
        </div>
        {detail.article ? <ArticleReader article={detail.article} scroll={scroll} body={body} topInset={topInset} />
          : <main className="detail-columns" aria-label="文章详情" aria-busy={detail.status === 'loading'}>
            <div className="detail-reader detail-state">
              {detail.status === 'loading' && <div className="feed-skeleton" role="status" aria-label="正在加载文章">
                <div className="skeleton-title" /><div className="skeleton-cover" /><div className="skeleton-summary" />
              </div>}
              {detail.status === 'missing' && <div><h1>文章不存在或已删除</h1><Link to="/">返回首页</Link></div>}
              {detail.status === 'error' && <div><p>{errorMessage(detail.error)}</p><button className="feed-action" disabled={seconds > 0}
                onClick={() => void detail.retry()}>{seconds ? `${seconds}秒后重试` : '重新加载'}</button></div>}
            </div>
          </main>}
      </div>
    </div>
  </div>;
}

/** 正文自然撑开文章卡片；目录与位置恢复显式区分内容范围及外层滚动框。 */
function ArticleReader({ article, scroll, body, topInset }: {
  article: ArticleDetailData; scroll: RefObject<HTMLDivElement | null>; body: RefObject<HTMLElement | null>; topInset: number;
}) {
  const location = useLocation();
  const unavailable = useUnavailable();
  const content = useMemo(() => prepareContent(article.contentHtml), [article.contentHtml]);
  const geometry = { contentRef: body, topInset };
  const toc = useArticleToc(scroll, body, content.entries, topInset);
  const stopRestoring = useScrollMemory(scroll, `detail:${location.key}`, true,
    location.state?.readingPosition as ReadingPosition | undefined, undefined, 0, geometry);
  return <main className="detail-columns" aria-label="文章详情">
    <div className="detail-actions-track"><ArticleActions article={article} reader={scroll} geometry={geometry} /></div>
    <section className="detail-reader" aria-label="文章阅读区域">
      <article className="detail-flow" ref={body}><ArticleHero article={article} /><ArticleBody content={content} /></article>
    </section>
    <aside className="detail-sidebar" aria-label="作者与目录">
      <section className="detail-author glass-panel" aria-label="文章作者">
        <div className="detail-author-row"><button className="author-entry" aria-label={`查看作者 ${article.authorName}`} onClick={unavailable}>
          <Avatar src={article.authorAvatarUrl} size={60} /><span className="detail-author-name" title={article.authorName}>{article.authorName}</span>
        </button>
          {/* 作者卡片始终保留关注入口，目前仅提供未开放反馈。 */}
          <button className="follow-button" onClick={unavailable}>+ 关注</button></div>
        <p>作者简介暂未开放</p>
      </section>
      <ArticleToc entries={content.entries} active={toc.active} onJump={(id) => { stopRestoring(); toc.jump(id); }} />
    </aside>
  </main>;
}
