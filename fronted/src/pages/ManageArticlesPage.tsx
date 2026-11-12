import { useEffect, useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { useToast, useUnavailable } from '../components/Toast';
import { ManageError, ManageLoadBoundary, ManageSkeleton } from '../components/manage/ManageStates';
import { ManageArticleList } from '../components/manage/ManageArticleList';
import { useMyArticles } from '../hooks/useMyArticles';
import { useCooldown } from '../hooks/useCooldown';
import { useArticleDeletion } from '../hooks/useArticleDeletion';
import { DeleteArticleDialog } from '../components/manage/DeleteArticleDialog';
import { ApiError } from '../api/client';
import './manage.css';

/** 以账号为键挂载本人会话，后台资料复核不会卸载列表或重置位置。 */
function MyArticles({ userId }: { userId: string }) {
  const auth = useAuth();
  const notify = useToast();
  const [recoveringAuth, setRecoveringAuth] = useState(false);
  const enabled = !auth.refreshing && !auth.error;
  const location = useLocation();
  const list = useMyArticles(userId, enabled, auth.expireSession, `manage:${location.key}`);
  const deletion = useArticleDeletion(enabled, auth.expireSession, (id, message) => {
    const controls = Array.from(document.querySelectorAll<HTMLButtonElement>('.manage-row [data-delete-id]'));
    const index = controls.findIndex((element) => element.dataset.deleteId === id);
    list.remove(id);
    notify(message);
    // 成功后原触发按钮已移除，等待React提交再聚焦相邻操作；保留列表位置。
    requestAnimationFrame(() => {
      const remaining = document.querySelectorAll<HTMLButtonElement>('.manage-row [data-delete-id]:not(:disabled)');
      const target = remaining[Math.min(Math.max(index, 0), remaining.length - 1)]
        ?? document.querySelector<HTMLButtonElement>('.manage-load-overlay button:not(:disabled)')
        ?? document.querySelector<HTMLButtonElement>('.manage-centered-state button:not(:disabled), .manage-toolbar button:not(:disabled)');
      target?.focus({ preventScroll: true });
    });
  });
  const deleteSeconds = useCooldown(deletion.state.retryAt);
  const seconds = useCooldown(list.retryAt);
  const identitySeconds = useCooldown(auth.error instanceof ApiError ? auth.error.retryAt : 0);
  const retry = async () => {
    if (!auth.error) { await list.refresh(); return; }
    // 明确重试身份失败时复用骨架；普通后台复核不增加可见状态。
    setRecoveringAuth(true);
    try { await auth.refresh(); } finally { setRecoveringAuth(false); }
  };
  return <>
    <div className="manage-toolbar"><h1>已发布</h1>
      <button className="manage-text-action" disabled={!enabled || !!deletion.state.article || seconds > 0 || list.status === 'refreshing'}
      onClick={() => { void list.refresh(); }}>{list.status === 'refreshing' ? '刷新中…' : seconds > 0 ? `${seconds}秒后重试` : '刷新'}</button></div>
    <ManageLoadBoundary failed={!!auth.error || list.status === 'refreshError'} loading={recoveringAuth}
      seconds={auth.error ? identitySeconds : seconds} onRetry={() => { void retry(); }}>
      <ManageArticleList list={list} enabled={enabled} paused={!!deletion.state.article} uncertainIds={deletion.uncertainIds} onDelete={deletion.open}
        scrollKey={`manage-scroll:${location.key}`} ownerId={userId} />
    </ManageLoadBoundary>
    <DeleteArticleDialog state={deletion.state} seconds={deleteSeconds} blocked={!enabled} onClose={deletion.close}
      onConfirm={() => { void deletion.confirm(); }} onCheck={() => { void deletion.check(); }} />
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
            {auth.status === 'error' ? <ManageError seconds={seconds} onRetry={() => { void auth.refresh(); }} /> : <ManageSkeleton />}
          </div></>}
      </main>
    </div>
  </div>;
}
