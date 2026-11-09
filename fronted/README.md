# Easy Community 前端

React + TypeScript + Vite，使用原生 CSS 与 React Router。目录名称沿用仓库中的 `fronted`。

## 启动与构建

建议使用 Node.js 24 LTS 和 npm。本次验证环境为 Node.js 24.18.0、npm 11.16.0；依赖版本以 `package-lock.json` 为准。

```powershell
cd fronted
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。Vite 将相对 `/api` 请求转发到 `http://127.0.0.1:8080`，保留 Host、路径前缀和 Cookie。开发时统一使用 localhost，避免与 127.0.0.1 混用导致 Cookie 隔离。

```powershell
npm run typecheck
npm test
npx playwright install chromium
npm run test:e2e
npm run build
```

浏览器测试在 5175 端口自动启动生产构建预览，不占用 5173。测试接口仅在 Playwright 浏览器内拦截，不依赖后端；失败保留截图和 trace，布局截图位于 `test-results/`。页面没有生产环境的假数据或模拟登录开关。

构建结果位于 `dist/`。部署时由静态服务器承载，前端路由回退 `index.html`，并在同一 Origin 下转发 `/api/` 到后端，可参考仓库 `backend/examples/nginx.conf.template`。`vite preview` 只用于本地验收。

## 已实现边界

- `/`：主页三栏、顶部与侧栏吸顶、静态横幅、推荐流连续加载。最小宽度 1440px，窄屏横向滚动，背景固定覆盖视口。
- `/login`：账号密码登录、必填反馈、密码显隐、CSRF、提交锁、限流倒计时与登录后返回首页。小于 1024px 时优先显示表单，短窗口允许纵向滚动。
- 真实接口：`GET /api/articles/feed`、`GET /api/users/me`、`GET /api/auth/csrf`、`POST /api/auth/login`。数据类型直接对应后端 DTO，ID 始终使用字符串。
- 搜索、热门、关注、发布管理、文章详情、点赞写入、评论和个人中心保留外观与未开放提示；不增加注册、找回密码、记住登录和退出入口。
- 作者使用默认头像，评论数显示“—”；浏览、点赞数量及本人点赞状态使用真实接口数据。

推荐流每次固定读取20项，以 `nextCursor=null` 判断结束；空页不等于结束。连续三页没有新文章时暂停自动请求，允许手动继续。失败保留内容，限流遵守 `Retry-After`；过期或无效游标需点击重新加载，重新从首屏开始。列表暂不虚拟化，持续浏览会增加 DOM 数量。

登录态来自用户资料接口；401 判定游客，503 或网络失败保留未知态。Session ID 仅通过 HttpOnly Cookie 传递，CSRF 和表单数据只留内存。认证成功后的读取异常不会重发登录。用户名去首尾空白并保留大小写，密码原样发送。

## 设计来源

Figma 文档 `Community`，页面 `community`。主页节点 `84:673`（状态稿 `84:444`），桌面登录页 `229:749`（状态稿 `229:746`）。执行时的 source fingerprint 分别为 `ef69268c3843b6d0` 和 `1be9cef5a66406d1`。本机 workunit 证据保留在已忽略的 `.figma_bridge_agent/`。

| 设计节点 | 实现位置与取舍 |
|---|---|
| `84:695` 顶部导航 | `src/components/Header.tsx`，搜索只提示未开放 |
| `84:674`、`84:696` 两侧栏 | `src/components/Sidebars.tsx`，按可用窗口吸顶，不沿用左栏固定长高度 |
| `84:694` 横幅 | Figma 节点2倍 PNG 导出，保持原稿裁切 |
| `84:710`、`84:713`、`84:608` 文章变体 | `src/components/ArticleCard.tsx`，覆盖有封面、无封面、点赞状态 |
| `229:749`、`229:788` 登录与状态 | `src/pages/LoginPage.tsx`，真实认证替代静态设计示意，增加窄屏适配 |

背景及默认头像为原始 image paint 导出；图标使用原稿 SVG 遮罩，颜色继承交互状态。主页背景居中 cover；登录纹理原图192×192，以89%尺寸（170.88px）平铺。Noto Sans SC 可变字体由 `@fontsource-variable/noto-sans-sc` 自托管，按 Unicode 范围加载，保留系统字体回退；字体许可随依赖包提供。

## 本次联调记录（2026-11-10）

- 真实代理读取：游客资料接口401，CSRF接口200并返回指定请求头名。
- 使用本机已有开发账号真实登录200，返回首页，用户资料200；验证 Session ID 轮换、HttpOnly，以及脚本无法读取 Session Cookie。账号和密码未写入测试或文档。
- 当前运行后端推荐流返回503、`ARTICLE_SEARCH_UNAVAILABLE`，前端正确显示不可用与重试。启用推荐流还需要后端配置 `ARTICLE_SEARCH_ENABLED=true`、有效的 `ARTICLE_CURSOR_SECRET`，以及可查询的 Elasticsearch 索引和后端依赖；配置说明见仓库搜索运维文档。本次未修改后端配置或基础设施。
- 自动化覆盖推荐分页互斥、短页/空页/去重、过期/无效游标、迟到响应隔离、资料未知态、表单与CSRF、限流、HttpOnly和布局。推荐成功分页由模拟接口验证，尚未验证当前运行后端的真实成功数据流。

设计次级文字对比度偏低，本轮保留原色，并提供可见键盘焦点和明确反馈；关注关系、作者真实头像与评论数量仍待后端业务与后续页面设计补齐。
