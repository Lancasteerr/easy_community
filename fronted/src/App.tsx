import { Navigate, Route, Routes } from 'react-router-dom';

/** 仅声明本轮页面；未知地址统一返回社区首页。 */
export function App() {
  return <Routes>
    <Route path="/" element={<main>Easy Community · 主页准备中</main>} />
    <Route path="/login" element={<main>账号密码登录</main>} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>;
}
