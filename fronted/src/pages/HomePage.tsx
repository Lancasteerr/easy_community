import { Header } from '../components/Header';
import { PublishPanel, Sidebar } from '../components/Sidebars';
import { useToast, useUnavailable } from '../components/Toast';
import banner from '../assets/home-banner.png';
import { Feed } from '../components/Feed';
import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import { errorMessage } from '../api/client';

/** 主页限定在视口内，横幅与两侧栏保持原位，文章列表负责内部纵向滚动。 */
export function HomePage() {
  const unavailable = useUnavailable();
  const auth = useAuth();
  const navigate = useNavigate();
  const notify = useToast();
  useEffect(() => {
    document.title = 'Easy Community';
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, []);
  const onAvatarClick = () => {
    if (auth.status === 'guest') navigate('/login');
    else if (auth.status === 'authenticated') notify('个人中心暂未开放');
    else {
      notify(auth.status === 'error' ? errorMessage(auth.error) : '正在确认登录状态，请稍后重试');
      void auth.refresh();
    }
  };
  const avatarLabel = auth.status === 'authenticated' ? `${auth.user!.username} 的个人中心`
    : auth.status === 'guest' ? '登录' : auth.status === 'loading' ? '正在确认登录状态' : '重新检查登录状态';
  return <div className="home-page">
    <Header avatarUrl={auth.user?.avatarUrl} avatarLabel={avatarLabel} onAvatarClick={onAvatarClick} />
    <div className="home-columns">
      <Sidebar />
      <main className="home-main" aria-label="推荐信息流">
        <button className="home-banner" aria-label="首页横幅" onClick={unavailable}><img src={banner} alt="城市出行主题插画" /></button>
        <Feed />
      </main>
      <PublishPanel />
    </div>
  </div>;
}
