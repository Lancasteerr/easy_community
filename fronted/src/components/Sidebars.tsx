import { Icon } from './Icon';
import { useToast, useUnavailable } from './Toast';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** 已选推荐入口保持当前位置，其余入口仅提供未开放反馈。 */
export function Sidebar() {
  const unavailable = useUnavailable();
  return <aside className="home-sidebar glass-panel" aria-label="首页导航">
    <h1 className="panel-heading">首页<span>INDEX</span></h1>
    <nav className="feed-nav" aria-label="信息流分类">
      <button aria-current="page" className="nav-item nav-selected"><Icon name="terminal" size={30} />推荐</button>
      <button className="nav-item" onClick={unavailable}><Icon name="trending" size={30} />热门</button>
      <button className="nav-item" onClick={unavailable}><Icon name="compass" size={30} />关注</button>
    </nav>
  </aside>;
}

export function PublishPanel() {
  const auth = useAuth();
  const notify = useToast();
  // 主动使用受保护入口可以去登录；与游客直接访问受保护 URL 回首页分开。
  const checkIdentity = (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (auth.status === 'loading' || auth.status === 'error') {
      event.preventDefault(); notify('正在确认登录状态，请稍后重试'); void auth.refresh();
    }
  };
  return <aside className="publish-panel glass-panel" aria-label="作品发布">
    <h2 className="panel-heading">作品发布<span>POST</span></h2>
    <div className="publish-options">
      <Link to={auth.status === 'guest' ? '/login' : '/articles/new'} state={auth.status === 'guest' ? { returnTo: '/articles/new' } : undefined}
        onClick={checkIdentity}><Icon name="publish" size={48} /><span>发图文</span></Link>
      <Link to={auth.status === 'guest' ? '/login' : '/manage/articles'} state={auth.status === 'guest' ? { returnTo: '/manage/articles' } : undefined}
        onClick={checkIdentity}><Icon name="inbox" size={48} /><span>管理作品</span></Link>
    </div>
  </aside>;
}
