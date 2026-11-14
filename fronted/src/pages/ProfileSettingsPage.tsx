import { useEffect, useRef } from 'react';
import { Navigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import type { UserProfile } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { Icon } from '../components/Icon';
import { useCooldown } from '../hooks/useCooldown';
import { ProfileSettingsForm } from '../profile/ProfileSettingsForm';
import './profile-settings.css';

/** 设置页沿用桌面画布，整页滚动；初次确认资料后才建立该账号的表单。 */
export function ProfileSettingsPage() {
  const auth = useAuth();
  const owner = useRef<UserProfile | null>(null);
  const seconds = useCooldown(auth.error instanceof ApiError ? auth.error.retryAt : 0);
  if (auth.user && !auth.refreshing && !auth.error && owner.current?.userId !== auth.user.userId) owner.current = auth.user;
  const hasProfile = auth.user && owner.current?.userId === auth.user.userId;

  useEffect(() => {
    document.title = '编辑资料 · Easy Community';
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, []);

  if (auth.status === 'guest') return auth.endReason === 'action'
    ? <Navigate to="/login" state={{ returnTo: '/settings/profile' }} replace /> : <Navigate to="/" replace />;

  return <div className="profile-settings-page">
    <Header />
    <div className="profile-settings-columns">
      <aside className="profile-settings-sidebar glass-panel" aria-label="设置导航">
        <h2 className="profile-settings-heading">设置<span>SETTINGS</span></h2>
        <nav aria-label="设置分类"><a href="/settings/profile" aria-current="page" className="profile-settings-nav">
          <Icon name="menuContent" size={30} />编辑资料
        </a></nav>
      </aside>
      <main className="profile-settings-panel glass-panel" aria-labelledby="profile-settings-heading">
        <h1 id="profile-settings-heading">编辑资料</h1>
        {hasProfile && auth.user && <ProfileSettingsForm key={auth.user.userId} profile={auth.user} />}
        {auth.error ? <div className="profile-settings-read-state" role="status">
          <p>资料暂时无法加载</p>
          <button type="button" disabled={auth.refreshing || seconds > 0} onClick={() => { void auth.refresh(); }}>重新加载</button>
        </div> : !hasProfile && <p className="profile-settings-read-state" role="status">正在加载资料…</p>}
      </main>
    </div>
  </div>;
}
