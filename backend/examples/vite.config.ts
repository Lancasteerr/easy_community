import { defineConfig } from 'vite';

// 合并到前端已有Vite配置；保持浏览器同源，不剥离后端已有的/api前缀。
export default defineConfig({
  server: {
    host: 'localhost',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        // 保留浏览器Host，dev后端忽略不可信转发头，不需要开放通配CORS。
        changeOrigin: false,
      },
    },
  },
});
