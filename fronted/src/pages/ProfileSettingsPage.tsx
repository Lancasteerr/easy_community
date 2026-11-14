import { SessionLoginRedirect } from '../auth/SessionLoginRedirect';
import { useEffect } from 'react';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { Icon } from '../components/Icon';
import { usePublicProfile } from '../profile/usePublicProfile';
import { ProfileSettingsForm } from '../profile/ProfileSettingsForm';
import './profile-settings.css';

/** 设置页沿用桌面画布，整页滚动；页面独立读取资料，离页后不保留输入。 */
export function ProfileSettingsPage() {
  const auth = useAuth();
  const { profile, failed } = usePublicProfile(auth.identity?.userId);

  useEffect(() => {
    document.title = '编辑资料 · Easy Community';
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, []);

  if (auth.status === 'guest') return <SessionLoginRedirect returnTo="/settings/profile" />;

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
        {profile && <ProfileSettingsForm key={profile.userId} profile={profile} />}
        {(failed || auth.error) ? <p className="profile-settings-read-state" role="status">加载失败，请稍后重试</p>
          : !profile && <p className="profile-settings-read-state" role="status">正在加载资料…</p>}

      </main>
    </div>
  </div>;
}
