# 开发环境、部署模板与验证入口

本目录按运行用途组织。以下命令均从仓库根目录执行；配置模板需要填写本机或目标环境参数，真实环境文件不提交。

| 用途 | 入口 | 边界 |
|---|---|---|
| Elasticsearch 开发服务 | [启动说明](dev/elasticsearch/README.md) | 独立 ES 容器，应用接入与索引维护另见公开运维文档 |
| MinIO 配置及历史 Caddy 上传联调 | [媒体开发说明](dev/media/README.md) | 日常前端使用 Vite 上传代理；历史 Compose 不自动启动 |
| 消息拓扑声明与核验 | `dev/like-topology.ps1`、`dev/search-topology.ps1` | 按公开点赞、搜索运维文档显式执行 |
| Web 站点部署模板 | [Web 部署说明](deploy/web/README.md) | Caddy → Nginx → 后端，静态资源使用前端 dist |
| COS 配置模板 | [生产媒体说明](deploy/media/README.md) | 只提供策略和 CORS 模板，不修改真实云资源 |
| 点赞与搜索功能、故障测试 | `test/run-like-tests.ps1`、`test/run-search-tests.ps1` | 仅使用各自的独立测试依赖；保留既有命令位置 |
| 媒体离线协议及真实 COS 验证 | [媒体验证说明](test/media/README.md) | 离线测试不代表真实云端验收 |
| 性能环境、运行、汇总与复核 | [性能说明](test/performance/README.md) | 原始结果保留在 artifacts/performance，与功能测试隔离 |

```powershell
# 离线上传协议测试，不连接真实存储。
node --test infra/test/media/support/upload.test.mjs
# 性能命令只在主动测量时运行；完整前提和时间见对应说明。
./infra/test/performance/run.ps1 -Suite Like -Seconds 30 -Rounds 1
```

可复用脚本按用途存入本目录。前端专用真实联调仍使用 `fronted/scripts/`；Agent 一次性诊断、探查和截图输出存入已忽略的 `artifacts/agent/<任务>/`。工具会话继续使用工具目录，coverage 只存覆盖率结果。既有产物去留及外部恢复入口见[整理报告](../docs/development/reports/maintenance/file-organization-2026-11-16.md)。
