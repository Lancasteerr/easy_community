import { Link, useNavigate } from 'react-router-dom';
import { Avatar } from './Avatar';
import { Icon } from './Icon';
import { useUnavailable } from './Toast';

/** 导航的布局与登录态读取分离，业务接入时只需替换头像点击行为。 */
export function Header({ avatarUrl, onAvatarClick, avatarLabel = '登录' }: {
  avatarUrl?: string | null; onAvatarClick?: () => void; avatarLabel?: string;
}) {
  const unavailable = useUnavailable();
  const navigate = useNavigate();
  return <header className="home-header glass-panel">
    <Link className="site-title" to="/">Easy Community</Link>
    <div className="header-actions">
      <form className="search-form" role="search" onSubmit={(event) => { event.preventDefault(); unavailable(); }}>
        <input type="search" aria-label="搜索内容" placeholder="搜索你感兴趣的内容" />
        <button type="submit" aria-label="搜索"><Icon name="search" size={16} /></button>
      </form>
      <div className="header-tools">
        <button className="publish-entry icon-button" aria-label="发布中心" onClick={unavailable}><Icon name="edit" size={25} /></button>
        <button className="avatar-entry icon-button" aria-label={avatarLabel} onClick={onAvatarClick ?? (() => navigate('/login'))}>
          <Avatar src={avatarUrl} size={40} />
        </button>
      </div>
    </div>
  </header>;
}
