import { Link, useNavigate } from 'react-router-dom';
import { Avatar } from './Avatar';
import { Icon } from './Icon';
import { useUnavailable } from './Toast';

/** 导航仅接收展示资料，头像行为由掌握登录态的使用页面决定。 */
export function Header({ avatarUrl, onAvatarClick, avatarLabel, hideSearch = false }: {
  avatarUrl?: string | null; onAvatarClick: () => void; avatarLabel: string; hideSearch?: boolean;
}) {
  const unavailable = useUnavailable();
  const navigate = useNavigate();
  return <header className="home-header glass-panel">
    <Link className="site-title" to="/">Easy Community</Link>
    <div className="header-actions">
      {!hideSearch && <form className="search-form" role="search" onSubmit={(event) => { event.preventDefault(); unavailable(); }}>
        <input type="search" aria-label="搜索内容" placeholder="搜索你感兴趣的内容" />
        <button type="submit" aria-label="搜索"><Icon name="search" size={16} /></button>
      </form>}
      <div className="header-tools">
        <button className="publish-entry icon-button" aria-label="发布中心" onClick={() => navigate('/articles/new')}><Icon name="edit" size={25} /></button>
        <button className="avatar-entry icon-button" aria-label={avatarLabel} onClick={onAvatarClick}>
          <Avatar src={avatarUrl} size={40} />
        </button>
      </div>
    </div>
  </header>;
}
