import { Navigate, Route, Routes } from 'react-router-dom';
import { ToastProvider } from './components/Toast';
import { HomePage } from './pages/HomePage';

/** 仅声明本轮页面；未知地址统一返回社区首页。 */
export function App() {
  return <ToastProvider><Routes>
    <Route path="/" element={<HomePage />} />
    <Route path="/login" element={<main>账号密码登录</main>} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></ToastProvider>;
}
