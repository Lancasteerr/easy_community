import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import type { ArticleDetailData } from '../api/types';
import { useArticleDetail } from '../article/useArticleDetail';
import { useCooldown } from '../hooks/useCooldown';
import { Header } from '../components/Header';
import { Avatar } from '../components/Avatar';
import { useUnavailable } from '../components/Toast';
import { ArticleHero } from '../article/ArticleHero';
import { ArticleBody } from '../article/ArticleBody';
import { ArticleToc } from '../article/ArticleToc';
import { prepareContent } from '../article/content';
import { useArticleToc } from '../article/useArticleToc';
import { capturePosition, useScrollMemory, type ReadingPosition } from '../navigation/useScrollMemory';
import { ArticleActions } from '../article/ArticleActions';
import { useDetailViewport } from '../article/useDetailViewport';
import { CommentSection } from '../article/comments/CommentSection';
import { articleCommentsPath, commentsAnchor } from '../article/routes';
import './detail.css';
import { profilePath } from '../profile/routes';

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
  const navigate = useNavigate();
  useEffect(() => { document.title = `${detail.article?.title ?? '文章详情'} · Easy Community`; }, [detail.article?.title]);
  useEffect(() => { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); }, []);
  const login = () => navigate('/login', { state: { returnTo: `/articles/${id}`,
    readingPosition: capturePosition(scroll.current, geometry) } });
  return <div className="detail-page">
    <div className="detail-scroll" ref={scroll} tabIndex={0} role="region" aria-label="文章详情滚动区域">
      <div className="detail-scroll-canvas">
        {/* 与主页保持相同的吸顶遮挡边界，卡片整体从导航背景下方经过。 */}
        <div className="detail-top-frame" ref={top}>
          <Header onLogin={login} />
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
  const navigate = useNavigate();
  const unavailable = useUnavailable();
  const comments = useRef<HTMLElement>(null);
  const [commentCount, setCommentCount] = useState(article.commentCount);
  const content = useMemo(() => prepareContent(article.contentHtml), [article.contentHtml]);
  const geometry = { contentRef: body, topInset };
  const toc = useArticleToc(scroll, body, content.entries, topInset);
  const stopRestoring = useScrollMemory(scroll, `detail:${location.key}`, true,
    location.state?.readingPosition as ReadingPosition | undefined, undefined, 0, geometry);
  const jumpToComments = useCallback(() => {
    const reader = scroll.current;
    const target = comments.current;
    if (!reader || !target) return;
    // 修改阅读容器的纵向坐标，保留页面及栏位的横向位置。
    stopRestoring();
    reader.scrollTop += target.getBoundingClientRect().top - reader.getBoundingClientRect().top - topInset - 24;
    target.focus({ preventScroll: true });
  }, [scroll, stopRestoring, topInset]);
  useLayoutEffect(() => {
    if (location.hash === commentsAnchor) jumpToComments();
  }, [location.key, location.hash, jumpToComments]);
  const loginToComment = () => navigate('/login', { state: { returnTo: articleCommentsPath(article.articleId) } });
  return <main className="detail-columns" aria-label="文章详情">
    <div className="detail-actions-track"><ArticleActions article={article} reader={scroll} geometry={geometry}
      commentCount={commentCount} onComments={jumpToComments} /></div>
    <section className="detail-reader" aria-label="文章阅读区域">
      <article className="detail-flow" ref={body}><ArticleHero article={article} /><ArticleBody content={content} /></article>
      <CommentSection articleId={article.articleId} authorId={article.authorId} count={commentCount}
        anchor={comments} onCount={setCommentCount} onLogin={loginToComment} />
    </section>
    <aside className="detail-sidebar" aria-label="作者与目录">
      <section className="detail-author glass-panel" aria-label="文章作者">
        <div className="detail-author-row"><Link className="author-entry" aria-label={`查看作者 ${article.authorName}`} to={profilePath(article.authorId)}>
          <Avatar src={article.authorAvatarUrl} size={60} /><span className="detail-author-name" title={article.authorName}>{article.authorName}</span>
        </Link>
          {/* 作者卡片始终保留关注入口，目前仅提供未开放反馈。 */}
          <button className="follow-button" onClick={unavailable}>+ 关注</button></div>
        <p>作者简介暂未开放</p>
      </section>
      <ArticleToc entries={content.entries} active={toc.active} onJump={(id) => { stopRestoring(); toc.jump(id); }} />
    </aside>
  </main>;
}
