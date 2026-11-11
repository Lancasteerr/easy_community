import { useEffect } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { useToast, useUnavailable } from '../components/Toast';
import { ManageError, ManageSkeleton } from '../components/manage/ManageStates';
import { ManageArticleList } from '../components/manage/ManageArticleList';
import { useMyArticles } from '../hooks/useMyArticles';
import { errorMessage } from '../api/client';
import { useCooldown } from '../hooks/useCooldown';
import { ApiError } from '../api/client';
import './manage.css';

/** 以账号为键挂载本人会话，后台资料复核不会卸载列表或重置位置。 */
function MyArticles({ userId }: { userId: string }) {
  const auth = useAuth();
  const unavailable = useUnavailable();
  const enabled = !auth.refreshing && !auth.error;
  const list = useMyArticles(userId, enabled, auth.expireSession);
  const seconds = useCooldown(list.retryAt);
  const identitySeconds = useCooldown(auth.error instanceof ApiError ? auth.error.retryAt : 0);
  return <>
    <div className="manage-toolbar"><h1>已发布</h1><button className="manage-text-action" disabled={!enabled || seconds > 0 || list.status === 'refreshing'}
      onClick={() => { void list.refresh(); }}>{list.status === 'refreshing' ? '刷新中…' : seconds > 0 ? `${seconds}秒后重试` : '刷新'}</button></div>
    {auth.refreshing && <div className="manage-notice" role="status">正在确认登录状态…</div>}
    {!!auth.error && <div className="manage-notice" role="status">登录状态暂时无法确认，操作已暂停<button className="manage-text-action" disabled={identitySeconds > 0}
      onClick={() => { void auth.refresh(); }}>{identitySeconds ? `${identitySeconds}秒后重试` : '重新检查登录状态'}</button></div>}
    <ManageArticleList list={list} enabled={enabled} onDelete={unavailable} />
  </>;
}

export function ManageArticlesPage() {
  const auth = useAuth();
  const notify = useToast();
  const unavailable = useUnavailable();
  const seconds = useCooldown(auth.error instanceof ApiError ? auth.error.retryAt : 0);
  useEffect(() => {
    document.title = '内容管理 · Easy Community';
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, []);
  if (auth.status === 'guest') return <Navigate to="/login" state={{ returnTo: '/manage/articles' }} replace />;
  return <div className="manage-page">
    <Header avatarUrl={auth.user?.avatarUrl} avatarLabel={auth.user ? `${auth.user.username} 的个人中心` : '重新检查登录状态'}
      onAvatarClick={() => { if (auth.user) notify('个人中心暂未开放'); else void auth.refresh(); }} />
    <div className="manage-columns"><aside className="manage-sidebar" aria-label="发布管理">
      <h2 className="manage-heading">发布管理<span>MANAGE</span></h2><nav className="manage-nav" aria-label="内容管理分类">
        <button aria-current="page">管理文章</button><button onClick={unavailable}>管理视频</button><button onClick={unavailable}>管理合集</button>
      </nav></aside>
      <main className="manage-panel" aria-label="文章管理">
        {auth.user ? <MyArticles key={auth.user.userId} userId={auth.user.userId} /> : <>
          <div className="manage-toolbar"><h1>已发布</h1></div><div className="manage-list">
            {auth.status === 'error' ? <ManageError message={errorMessage(auth.error)} seconds={seconds} onRetry={() => { void auth.refresh(); }} /> : <ManageSkeleton />}
          </div></>}
      </main>
    </div>
  </div>;
}
