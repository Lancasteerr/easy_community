import { useEffect, useRef } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { useCooldown } from '../hooks/useCooldown';
import { useProfileDetails } from '../profile/useProfileDetails';
import { useProfileArticles } from '../profile/useProfileArticles';
import { useProfileViewport } from '../profile/useProfileViewport';
import { ProfileHeader } from '../profile/ProfileHeader';
import { ProfileInfoCard } from '../profile/ProfileInfoCard';
import { ProfileArticleList } from '../profile/ProfileArticleList';
import './profile.css';
import { useScrollMemory } from '../navigation/useScrollMemory';

/** 每个作者拥有独立页面会话，切换路由先卸载旧资料、请求与滚动监听。 */
export function ProfilePage() {
  const { userId } = useParams();
  return <ProfileSession key={userId} userId={userId} />;
}

function ProfileSession({ userId }: { userId: string | undefined }) {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const scroll = useRef<HTMLDivElement>(null);
  const top = useRef<HTMLDivElement>(null);
  const details = useProfileDetails(userId);
  const list = useProfileArticles(userId ?? '', details.status === 'ready', `profile:${userId}:${location.key}`);
  const { getTopInset } = useProfileViewport(scroll, top, details.status === 'ready');
  // 公开列表按作者和历史项恢复；登录会整页导航，不接受来自旧文档的列表快照。
  useScrollMemory(scroll, `profile-scroll:${userId}:${location.key}`, details.status === 'ready' && list.status !== 'initialLoading',
    undefined, undefined, list.revision, { getTopInset });
  const seconds = useCooldown(details.retryAt);
  const self = !!auth.identity && auth.identity.userId === userId;
  const blocked = auth.status !== 'authenticated' || auth.refreshing || !!auth.error;
  const known = auth.status === 'guest' || auth.status === 'authenticated';
  const login = () => navigate('/login', { state: { returnTo: `/users/${userId}` } });
  useEffect(() => { document.title = `${details.profile?.username ?? '个人主页'} · Easy Community`; }, [details.profile?.username]);
  useEffect(() => { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); }, []);
  const props = details.profile ? { profile: details.profile, self, known, blocked } : null;
  return <div className="profile-page">
    <div className="profile-scroll" ref={scroll} role="region" tabIndex={0} aria-label="个人主页滚动区域">
      <div className="profile-canvas">
        <div className="profile-top-frame" ref={top}><Header onLogin={login} />
          {props && <div className="profile-header-positioner"><ProfileHeader {...props} /></div>}
        </div>
        <main className="profile-main">
          {props ? <>
            <div className="profile-header-placeholder" aria-hidden="true" />
            <div className="profile-columns"><ProfileArticleList list={list} scroll={scroll} self={self} canPublish={!blocked} onLogin={login} />
              <ProfileInfoCard profile={props.profile} /></div>
          </> : <section className="profile-read-state glass-panel" aria-busy={details.status === 'loading'}>
            {details.status === 'loading' && <div className="feed-skeleton" role="status" aria-label="正在加载用户资料">
              <div className="skeleton-cover" /><div className="skeleton-title" /><div className="skeleton-summary" /></div>}
            {details.status === 'missing' && <><h1>用户不存在</h1><Link to="/">返回首页</Link></>}
            {details.status === 'error' && <><p>加载失败，请稍后重试</p><button className="feed-action" disabled={seconds > 0}
              onClick={() => { void details.retry(); }}>{seconds ? `${seconds}秒后重试` : '重新加载'}</button></>}
          </section>}
        </main>
      </div>
    </div>
  </div>;
}
