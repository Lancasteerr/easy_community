import { useEffect, useRef } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { useToast } from '../components/Toast';
import { ArticleEditorSession } from '../editor/ArticleEditorSession';
import { EditorReadState } from '../editor/EditorReadState';
import './editor.css';

/** 外壳不以文章 ID 为 key，新建成功后沿用同一编辑会话。 */
export function ArticleEditorPage() {
  const { id } = useParams();
  const auth = useAuth();
  const notify = useToast();
  // 失效后继续挂载原作者会话，由会话先完成暂存再导航；不能直接因 user=null 卸载。
  const owner = useRef(auth.user);
  if (!owner.current && auth.user) owner.current = auth.user;
  useEffect(() => { document.title = `${id ? '编辑文章' : '发布文章'} · Easy Community`; }, [id]);
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, []);
  if (auth.status === 'guest' && !owner.current) return <Navigate to="/login" replace state={{ returnTo: id ? `/articles/${id}/edit` : '/articles/new' }} />;
  return <div className="article-editor-page">
    {/* 与文章详情页一致：整页共用滚动口，白色卡片由内容自然撑高。 */}
    <div className="article-editor-scroll" tabIndex={0} role="region" aria-label="文章编辑滚动区域">
      <div className="article-editor-canvas">
        {/* 顶部背景随导航吸顶，遮挡从下方经过的编辑卡片。 */}
        <div className="article-editor-top-frame">
          <Header hideSearch avatarUrl={auth.user?.avatarUrl} avatarLabel="个人中心" onAvatarClick={() => notify('个人中心暂未开放')} />
        </div>
        <main className="article-editor-panel" aria-label="文章编辑区域">
          {owner.current ? <ArticleEditorSession key={owner.current.userId} ownerId={owner.current.userId} routeId={id} />
            : <EditorReadState state={auth.status === 'error' ? 'error' : 'loading'} onRetry={() => { void auth.refresh(); }} />}
        </main>
      </div>
    </div>
  </div>;
}
