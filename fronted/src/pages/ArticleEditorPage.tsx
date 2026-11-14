import { SessionLoginRedirect } from '../auth/SessionLoginRedirect';
import { useCallback, useEffect, useRef } from 'react';
import { useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import type { EditorLogoutGuard } from '../editor/useEditorLeaveGuard';
import { ArticleEditorSession } from '../editor/ArticleEditorSession';
import { EditorReadState } from '../editor/EditorReadState';
import './editor.css';

/** 外壳不以文章 ID 为 key，新建成功后沿用同一编辑会话。 */
export function ArticleEditorPage() {
  const { id } = useParams();
  const auth = useAuth();
  const logoutGuard = useRef<EditorLogoutGuard | null>(null);
  const registerLogoutGuard = useCallback((guard: EditorLogoutGuard | null) => { logoutGuard.current = guard; }, []);
  // 身份失效由页面边界清理，编辑页只使用当前已确认的操作人。
  const ownerId = auth.identity?.userId;
  useEffect(() => { document.title = `${id ? '编辑文章' : '发布文章'} · Easy Community`; }, [id]);
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, []);
  if (auth.status === 'guest') return <SessionLoginRedirect returnTo={id ? `/articles/${id}/edit` : '/articles/new'} />;
  return <div className="article-editor-page">
    {/* 与文章详情页一致：整页共用滚动口，白色卡片由内容自然撑高。 */}
    <div className="article-editor-scroll" tabIndex={0} role="region" aria-label="文章编辑滚动区域">
      <div className="article-editor-canvas">
        {/* 顶部背景随导航吸顶，遮挡从下方经过的编辑卡片。 */}
        <div className="article-editor-top-frame">
          <Header hideSearch beforeLogout={() => logoutGuard.current?.confirm() ?? Promise.resolve(false)}
            onLogoutSuccess={() => logoutGuard.current?.complete()} />
        </div>
        <main className="article-editor-panel" aria-label="文章编辑区域">
          {ownerId ? <ArticleEditorSession key={ownerId} ownerId={ownerId} routeId={id} registerLogoutGuard={registerLogoutGuard} />
            : <EditorReadState state={auth.status === 'error' ? 'error' : 'loading'} onRetry={() => { void auth.refresh(); }} />}
        </main>
      </div>
    </div>
  </div>;
}
