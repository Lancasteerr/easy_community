import { Header } from '../components/Header';
import { PublishPanel, Sidebar } from '../components/Sidebars';
import { useUnavailable } from '../components/Toast';
import banner from '../assets/home-banner.png';
import { Feed } from '../components/Feed';

/** body 承担双向滚动，吸顶元素的祖先不设置 overflow，避免改变滚动容器。 */
export function HomePage() {
  const unavailable = useUnavailable();
  return <div className="home-page">
    <Header />
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
