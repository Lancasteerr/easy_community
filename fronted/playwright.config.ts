import { defineConfig } from '@playwright/test';

// 浏览器验收独立启动测试端口，不依赖真实后端数据，也不占用日常开发端口。
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: 2,
  timeout: 30_000,
  use: {
    baseURL: 'http://127.0.0.1:5175',
    viewport: { width: 1440, height: 1024 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    // 验收实际生产产物；开发期 StrictMode 重放另外由组件测试覆盖。
    command: 'npm run build -- --logLevel warn && npm run preview -- --host 127.0.0.1 --port 5175 --strictPort',
    url: 'http://127.0.0.1:5175',
    reuseExistingServer: false,
  },
});
