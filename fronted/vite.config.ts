import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 浏览器始终同源访问 /api，代理保留 Cookie、Host 和后端路径前缀。
export default defineConfig({
  plugins: [react()],
  server: {
    host: 'localhost',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    clearMocks: true,
  },
});
