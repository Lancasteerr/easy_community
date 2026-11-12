import React from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { App } from './App';
import '@fontsource-variable/noto-sans-sc';
import './styles.css';

// 严格模式会重放开发期副作用，页面请求必须支持取消与重新建立。
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><RouterProvider router={createBrowserRouter([{ path: '*', element: <App /> }])} /></React.StrictMode>,
);
