import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 浏览器始终同源访问 /api，代理保留 Cookie、Host 和后端路径前缀。
export default defineConfig({
  plugins: [react()],
  server: {
    // 显式绑定 IPv4，避免 localhost 被解析为 ::1 后无法通过 IPv4 连接。
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } },
  },
  preview: { host: '127.0.0.1' },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    clearMocks: true,
  },
});
