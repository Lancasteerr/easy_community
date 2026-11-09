import { Icon } from './Icon';
import { useUnavailable } from './Toast';

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
  const unavailable = useUnavailable();
  return <aside className="publish-panel glass-panel" aria-label="作品发布">
    <h2 className="panel-heading">作品发布<span>POST</span></h2>
    <div className="publish-options">
      <button onClick={unavailable}><Icon name="publish" size={48} /><span>发图文</span></button>
      <button onClick={unavailable}><Icon name="inbox" size={48} /><span>管理作品</span></button>
    </div>
  </aside>;
}
