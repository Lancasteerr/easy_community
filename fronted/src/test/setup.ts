import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// 每个测试独立卸载页面，确保观察器和读取请求能正常清理。
afterEach(cleanup);
