import { Link, useNavigate } from 'react-router-dom';
import { Avatar } from './Avatar';
import { Icon } from './Icon';
import { useToast, useUnavailable } from './Toast';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../api/client';
import { usePublicProfile } from '../profile/usePublicProfile';
import { AvatarMenu } from './AvatarMenu';
import type { LogoutOptions } from '../auth/useLogout';

/** 导航统一解释登录态；阅读位置和编辑器退出确认仍由使用页面提供。 */
export function Header({ onLogin, hideSearch = false, ...logoutOptions }: LogoutOptions & { onLogin?: () => void; hideSearch?: boolean }) {
  const unavailable = useUnavailable();
  const notify = useToast();
  const auth = useAuth();
  const navigate = useNavigate();
  const { profile } = usePublicProfile(auth.identity?.userId);
  const login = () => { if (onLogin) onLogin(); else navigate('/login'); };
  const checkIdentity = () => { notify(auth.status === 'error' ? errorMessage(auth.error) : '正在确认登录状态，请稍后重试'); void auth.refresh(); };
  return <header className="home-header glass-panel">
    <Link className="site-title" to="/">Easy Community</Link>
    <div className="header-actions">
      {!hideSearch && <form className="search-form" role="search" onSubmit={(event) => { event.preventDefault(); unavailable(); }}>
        <input type="search" aria-label="搜索内容" placeholder="搜索你感兴趣的内容" />
        <button type="submit" aria-label="搜索"><Icon name="search" size={16} /></button>
      </form>}
      <div className="header-tools">
        <button className="publish-entry icon-button" aria-label="发布中心" onClick={() => {
          if (auth.status === 'guest') navigate('/login', { state: { returnTo: '/articles/new' } });
          else if (auth.status === 'authenticated') navigate('/articles/new'); else checkIdentity();
        }}><Icon name="edit" size={25} /></button>
        {auth.status === 'authenticated' && auth.identity ? <AvatarMenu userId={auth.identity.userId} avatarUrl={profile?.avatarUrl} label={profile ? `${profile.username} 的个人中心` : '个人中心'} {...logoutOptions} />
          : auth.status === 'guest' ? <button type="button" className="header-login" onClick={login}>登录</button>
          : <button type="button" className="avatar-entry icon-button" aria-label={auth.status === 'loading' ? '正在确认登录状态' : '重新检查登录状态'}
            aria-busy={auth.status === 'loading'} onClick={checkIdentity}><Avatar size={40} /></button>}
      </div>
    </div>
  </header>;
}
