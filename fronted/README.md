# Easy Community 前端

React + TypeScript + Vite，使用原生 CSS、React Router 和 Tiptap。目录名称沿用 `fronted`。

## 启动、测试与部署

使用 Node.js 24 LTS 和 npm，依赖以 package-lock.json 为准。在前端目录执行：

```powershell
npm ci
npm run dev
# 静态检查、单元测试和生产构建。
npm run typecheck
npm test
npm run build
# 需要浏览器回归时单独执行，不作为普通构建前提。
npx playwright install chromium
npm run test:e2e
```

开发地址为 `http://127.0.0.1:5173`。正式 [Vite 配置](vite.config.ts)监听 IPv4，将相对 `/api` 请求转发到后端 8080，保留路径、Host 和 Cookie。统一使用 127.0.0.1，避免与 localhost 混用。

浏览器测试在 5175 自动启动生产构建预览，通过 Playwright 拦截接口，不依赖真实后端。失败截图和 trace 保存在 test-results。本轮目录整理不执行浏览器测试，现有详情滚动失败证据继续保留；历史结果见[主页收缩报告](../docs/development/reports/frontend/profile-collapse-validation-2026-11-15.md)。

构建输出为 dist。生产由静态服务器承载，站内路由回退 index.html，在同一 Origin 转发 `/api/`；参考 [Web 部署模板](../infra/deploy/web/README.md)。vite preview 仅用于本地验收。

## 页面与当前边界

| 路由 | 行为 |
|---|---|
| `/` | 推荐流连续加载、共享导航与侧栏，浏览和点赞使用真实接口 |
| `/search` | 标题搜索、相关度/最新排序、游标续页和历史位置恢复；见[搜索页报告](../docs/development/reports/search/search-page-acceptance-2026-11-15.md) |
| `/login` | Session/CSRF 登录、限流倒计时与合法站内地址回跳 |
| `/manage/articles` | 本人公开文章、内部滚动、刷新、续页、删除确认与结果核对 |
| `/users/:userId` | 公开资料与作者作品，单 DOM 资料卡连续收缩，文章返回恢复位置 |
| `/settings/profile` | 简介与头像编辑；成功后整页刷新读取资料 |
| `/articles/new`、`/articles/:id/edit` | Tiptap 正文、封面及正文图片、标签、保存与普通离页确认 |
| `/articles/:id` | 公开文章阅读、目录、浏览和点赞，以及阅读位置恢复 |

共享身份仅来自 `GET /api/auth/session`，资料通过公开用户接口单独读取。网络或 503 不当作游客；Session ID 只经 HttpOnly Cookie 传递，CSRF 仅保存在内存。所有写操作使用当前 CSRF，不自动重发文章、上传或点赞。

身份失效、退出或换号时，公开页面原址整页刷新；受保护页进入显式登录并使用站内白名单回跳。取消在途请求并作废旧回调，不保存或恢复身份失效前的编辑内容；普通离页确认保留。窗口聚焦复核身份，往返缓存恢复旧文档时重新加载，不新增跨标签广播或草稿缓存。

列表每页20条，通过 nextCursor 判断结束，空页仍可能续读；去重、重复或失效游标、连续无新增和429均有明确处理。读取失败保留已有内容，列表未虚拟化。删除结果未知时由用户主动通过作者接口核对，不自动重发删除。

当前主要页面沿用1440px桌面画布，窄屏横向浏览；登录页另有窄屏适配。作者资料、作品、头像和浏览/点赞来自真实接口。个人主页文章12、关注19、粉丝1.2k是已约定的集中样例统计，与真实作品列表独立；关注、评论、收藏等未开放行为沿用提示或占位。此次整理不改变这些产品行为。

## 开发上传与真实联调

1. 复制 `.env.development.example` 为 `.env.development.local`，填写实际 `MEDIA_DEV_MINIO_TARGET` 与 `MEDIA_DEV_BUCKET`。
2. 将后端实际开发环境中的 `MEDIA_STORAGE_UPLOAD_ENDPOINT` 设为 `http://127.0.0.1:5173/__media/upload`，重新启动后端与 Vite。存储端点、公开地址和 Webhook 保持实际配置。
3. 浏览器原样使用授权 URL；[开发上传代理](dev/mediaUploadProxy.ts)只转换固定桶 POST 响应，不发送社区凭证，不等待 Webhook 或 READY，也不自动重传。

文章和头像上传均无固定延迟或就绪轮询。头像即时绑定409按普通失败处理；图片暂不可读可手动重载封面预览，保存失败保留输入。新建响应丢失后手动重发仍可能重复创建。

```powershell
# 仅主动进行真实联调时运行，后端、MinIO 和 Webhook 必须已就绪。
node scripts/verify-article-editor-live.mjs
```

脚本读取本机 backend/.env 的管理员初始化凭证，也支持 `EDITOR_LIVE_ADMIN_USERNAME/PASSWORD`。它使用5177端口和专用临时账号、文章，结束后解绑图片、隐藏文章并删除临时作者；凭证只在内存中使用。普通单元测试及浏览器模拟测试不执行该入口。真实 COS 验证见[媒体验证说明](../infra/test/media/README.md)。

## 设计与历史验收

设计来自 Figma `Community / community`；正式素材位于 src/assets，工具会话、设计指纹与独有截图保留在已忽略的 .figma_bridge_agent。详细节点、阶段结果和未完成事项见[前端交付历史](../docs/development/reports/frontend/frontend-delivery-history-2026-11-16.md)，其中旧正文和旧样式不代表当前行为。

- [内容管理验收](../docs/development/reports/frontend/content-management-validation-2026-11-11.md)：保留当时真实索引联调未通过的结论。
- [编辑页初次交付](../docs/development/reports/frontend/article-editor-validation-2026-11-12.md)与[白色编辑页](../docs/development/reports/frontend/article-editor-white-style-validation-2026-11-13.md)：白色主题取代早期样式，历史草稿恢复已由当前身份边界替代。
- [个人主页](../docs/development/reports/frontend/public-profile-validation-2026-11-15.md)与[收缩修正](../docs/development/reports/frontend/profile-collapse-validation-2026-11-15.md)：保留真实读取、模拟交互及既有失败的区别。

可复用前端联调脚本放 scripts；测试数据与 mock 继续服务现有测试，不作为无用示例清理。一次性 Agent 输出改放已忽略的 artifacts/agent（相对仓库根目录），coverage 只存覆盖率结果。已清理快照的外部备份与恢复方法见[整理报告](../docs/development/reports/maintenance/file-organization-2026-11-16.md)。
