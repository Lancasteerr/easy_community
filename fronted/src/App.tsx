import { Navigate, Route, Routes } from 'react-router-dom';
import { ToastProvider } from './components/Toast';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { AuthProvider } from './auth/AuthContext';
import { ManageArticlesPage } from './pages/ManageArticlesPage';
import { ArticleEditorPage } from './pages/ArticleEditorPage';

/** 仅声明本轮页面；未知地址统一返回社区首页。 */
export function App() {
  return <ToastProvider><AuthProvider><Routes>
    <Route path="/" element={<HomePage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/manage/articles" element={<ManageArticlesPage />} />
    <Route path="/articles/new" element={<ArticleEditorPage />} />
    <Route path="/articles/:id/edit" element={<ArticleEditorPage />} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></AuthProvider></ToastProvider>;
}
