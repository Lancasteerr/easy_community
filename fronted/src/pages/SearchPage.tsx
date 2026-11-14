import { useEffect, useLayoutEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Header } from '../components/Header';
import { PublishPanel } from '../components/Sidebars';
import { SearchForm } from '../search/SearchForm';
import { SearchResults } from '../search/SearchResults';
import { readSearchQuery, searchKeywordError, searchPath, type SearchSort } from '../search/routes';
import './search.css';

/** 搜索控件固定在导航下方，文章和右侧发布入口共享主页式通栏滚动。 */
export function SearchPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const scroll = useRef<HTMLDivElement>(null);
  const { q, sort } = readSearchQuery(location.search);
  const invalid = q ? searchKeywordError(q) : null;
  const enabled = !!q && !invalid;
  const destination = enabled ? searchPath(q, sort) : '/search';
  const login = () => navigate('/login', { state: { returnTo: destination } });
  useEffect(() => {
    document.title = `${enabled ? `${q} · 搜索` : '搜索'} · Easy Community`;
  }, [q, enabled]);
  useLayoutEffect(() => {
    const root = scroll.current;
    if (!root) return;
    const syncSize = () => root.style.setProperty('--search-viewport-height', `${root.clientHeight}px`);
    syncSize();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(syncSize);
    observer?.observe(root);
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    return () => observer?.disconnect();
  }, []);
  useLayoutEffect(() => {
    // 新条件先回顶，POP 的结果会话在内容就绪后自行恢复历史位置；不修改横向位置。
    if (scroll.current) scroll.current.scrollTop = 0;
  }, [location.key]);
  const submit = (keyword: string) => {
    if (keyword !== q) navigate(searchPath(keyword));
  };
  const changeSort = (value: SearchSort) => {
    if (enabled && value !== sort) navigate(searchPath(q, value));
  };
  return <div className="search-page">
    <div className="search-scroll" ref={scroll} tabIndex={0} role="region" aria-label="搜索页滚动区域">
      <div className="search-canvas">
        <div className="search-top-frame">
          <Header hideSearch onLogin={login} />
          {/* 控件伸出导航占位，吸顶背景只遮挡左列，不覆盖右侧发布卡片。 */}
          <div className="search-controls-positioner"><div className="search-controls">
            <SearchForm key={location.key} keyword={q} onSearch={submit} />
            <div className="search-sort" role="group" aria-label="搜索排序">
              <button type="button" aria-pressed={sort === 'relevance'} disabled={!enabled} onClick={() => changeSort('relevance')}>相关度</button>
              <button type="button" aria-pressed={sort === 'latest'} disabled={!enabled} onClick={() => changeSort('latest')}>最新</button>
            </div>
          </div></div>
        </div>
        <div className="search-columns">
          <main className="search-main" aria-label="文章搜索">
            <div className="search-controls-placeholder" aria-hidden="true" />
            {enabled ? <SearchResults key={`${location.key}:${sort}:${q}`} q={q} sort={sort} historyKey={location.key} scroll={scroll} onLogin={login} />
              : <section className="search-results search-empty" aria-label="搜索结果"><h1>{invalid ?? '搜索感兴趣的文章'}</h1>
                <p>{invalid ? '修改关键词后重新搜索' : '输入关键词，发现社区中的精彩内容'}</p></section>}
          </main>
          <PublishPanel />
        </div>
      </div>
    </div>
  </div>;
}
