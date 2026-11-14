import { documentNavigation } from '../navigation/documentNavigation';
import { pageLifetime } from '../auth/pageLifetime';
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';

// 每个测试独立卸载页面，确保观察器和读取请求能正常清理。
afterEach(cleanup);
// 只替换整页导航边界，不启动浏览器交互验收。
vi.mock('../navigation/documentNavigation', () => ({ documentNavigation: { reload: vi.fn(), replace: vi.fn() } }));
// jsdom 没有真实滚动布局；页面滚动与吸顶交由浏览器测试验证。
if (typeof window !== 'undefined') window.scrollTo = vi.fn();

beforeEach(() => { if (typeof window !== 'undefined') window.history.replaceState(null, '', '/'); vi.mocked(documentNavigation.reload).mockClear(); vi.mocked(documentNavigation.replace).mockClear(); pageLifetime.controller.abort(); pageLifetime.controller = new AbortController(); pageLifetime.onUnauthorized = null; });
