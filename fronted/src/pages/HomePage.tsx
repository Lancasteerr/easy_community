import { Header } from '../components/Header';
import { PublishPanel, Sidebar } from '../components/Sidebars';
import { useToast, useUnavailable } from '../components/Toast';
import banner from '../assets/home-banner.png';
import { Feed } from '../components/Feed';
import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';
import { useEffect, useLayoutEffect, useRef } from 'react';
import { errorMessage } from '../api/client';

/** 首页共用覆盖视口的滚动容器，导航与侧栏吸顶，中间内容在导航下方裁切。 */
export function HomePage() {
  const scrollContainer = useRef<HTMLDivElement>(null);
  const unavailable = useUnavailable();
  const auth = useAuth();
  const navigate = useNavigate();
  const notify = useToast();
  useLayoutEffect(() => {
    const container = scrollContainer.current;
    if (!container) return;
    // 扣除顶部布局后确定侧栏高度，兼容窄屏横向滚动条对可视高度的占用。
    const syncSize = () => {
      container.style.setProperty('--home-viewport-height', `${container.clientHeight}px`);
    };
    syncSize();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(syncSize);
    observer?.observe(container);
    return () => observer?.disconnect();
  }, []);
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
    <div className="home-scroll" ref={scrollContainer} tabIndex={0} role="region" aria-label="首页滚动区域">
      <div className="home-scroll-canvas">
        {/* 顶部背景与导航共同吸顶，由浏览器直接遮挡滚动内容，避免 JS 裁切滞后一帧。 */}
        <div className="home-top-frame">
          <Header avatarUrl={auth.user?.avatarUrl} avatarLabel={avatarLabel} onAvatarClick={onAvatarClick} />
        </div>
        <div className="home-columns">
          <Sidebar />
          <main className="home-main" aria-label="推荐信息流">
            <button className="home-banner" aria-label="首页横幅" onClick={unavailable}><img src={banner} alt="城市出行主题插画" /></button>
            <Feed scrollContainer={scrollContainer} />
          </main>
          <PublishPanel />
        </div>
      </div>
    </div>
  </div>;
}
