import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// 每个测试独立卸载页面，确保观察器和读取请求能正常清理。
afterEach(cleanup);
// jsdom 没有真实滚动布局；页面滚动与吸顶交由浏览器测试验证。
if (typeof window !== 'undefined') window.scrollTo = vi.fn();
