import { Navigate, Route, Routes } from 'react-router-dom';
import { ToastProvider } from './components/Toast';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { AuthProvider } from './auth/AuthContext';
import { ManageArticlesPage } from './pages/ManageArticlesPage';
import { lazy, Suspense } from 'react';

// 编辑器按路由加载，主页和登录页不承担正文编辑依赖的下载成本。
const ArticleEditorPage = lazy(() => import('./pages/ArticleEditorPage').then((module) => ({ default: module.ArticleEditorPage })));
// 阅读路由独立拆包，不让首页预先下载正文处理及高亮依赖。
const ArticleDetailPage = lazy(() => import('./pages/ArticleDetailPage').then((module) => ({ default: module.ArticleDetailPage })));

/** 仅声明本轮页面；未知地址统一返回社区首页。 */
export function App() {
  return <ToastProvider><AuthProvider><Routes>
    <Route path="/" element={<HomePage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/manage/articles" element={<ManageArticlesPage />} />
    <Route path="/articles/new" element={<Suspense fallback={<p role="status">正在加载编辑器…</p>}><ArticleEditorPage /></Suspense>} />
    <Route path="/articles/:id/edit" element={<Suspense fallback={<p role="status">正在加载编辑器…</p>}><ArticleEditorPage /></Suspense>} />
    <Route path="/articles/:id" element={<Suspense fallback={<p role="status">正在加载文章…</p>}><ArticleDetailPage /></Suspense>} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></AuthProvider></ToastProvider>;
}
