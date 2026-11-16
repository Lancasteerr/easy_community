# 项目文件与 Agent 中间产物整理（2026-11-16）

本报告记录目录整理及历史产物去留，不代表业务功能重新验收。业务 API、页面和性能基准实现均不改变。既有未提交性能成果继续保留，不混入整理提交。

## 归档批次与恢复

- 批次：`20261116-142816`。
- 仓库外位置：`E:\codes\java\easy_community\_agent_archives\easy_community\20261116-142816`。
- `manifest.json`：原相对路径、文件大小、SHA-256、修改时间、分组及处理理由；`files/` 保持原目录结构。
- `snapshot-comparison.json`：四套快照与正式前端的逐文件比较；`duplicate-assets.json`：重复设计导出与正式素材的对应关系。
- `protected-hashes.json`：正式性能运行、性能源码与报告附件、工具会话、个人主页证据和浏览器失败产物的整理前哈希。
- `initial-working-tree.patch`、`initial-status.txt`：原有工作区状态备份，不纳入本轮提交。

已完成复制校验：11 个文件组、1139 个普通文件，共 69,944,058 字节（约 66.7 MiB），另记录 2 个目录联接。复制前后检查源文件哈希和修改时间，归档副本逐文件检查大小及 SHA-256。清理前再次检查源文件集合和哈希；任一组发生变化则保留源组。

初始状态：归档副本已校验，待执行源目录清理。实际结果和验证见本文后续更新及归档中的 `cleanup-result.json`。

恢复时先按清单校验归档文件，在原相对路径不存在时复制回仓库；存在不同内容时先比较，不覆盖当前文件。复制后再次校验 SHA-256。两个 `node_modules` 联接仅保存目标信息，不复制依赖，也不自动重建；需要重建时，先确认正式 `fronted/node_modules` 存在，再根据清单手动创建联接。归档包含历史运行记录，不是当前版本测试结果。

## 去留清单

| 文件组 | 用途、依赖与引用 | 独有内容与决定 |
|---|---|---|
| `.figma_bridge_agent/baseline/`，278 文件 | 历史前端基线；正式构建和测试不引用 | 含与现版本不同的源码，完整归档后清理 |
| `.figma_bridge_agent/collapse-baseline/`，286 文件 | 收缩交互历史基线；验收报告保留旧失败结论 | 完整归档后清理，不删除正式失败证据 |
| `fronted/coverage/banner-baseline/`，257 文件 | 放错位置的前端源码及构建快照 | 完整归档后清理，coverage 不再作为源码备份目录 |
| `fronted/coverage/like-baseline/`，247 文件 | 点赞改动前端快照；正式测试不引用 | 同上 |
| `.figma_bridge_agent/skland-probe-2026-11-15/`，27 文件 | 第三方站点下载脚本；[收缩报告](../frontend/profile-collapse-validation-2026-11-15.md)已记录参考出处 | 有第三方独有内容，但不属于本项目运行依赖；归档后清理 |
| `.figma_bridge_agent/validation/`，5 文件 | 一次性 ES 只读诊断、管理页联调和设计映射记录脚本；[管理页报告](../frontend/content-management-validation-2026-11-11.md)引用 | 完整归档后清理；不执行这些会写业务数据或工具状态的历史脚本 |
| `.figma_bridge_agent/sessions/` | 工具会话、设计事实、源指纹、交付映射 | 原地保留；旧日期不足以判定无用，不改工具状态 |
| `.figma_bridge_agent/profile-evidence/`、`profile-collapse-evidence/` | 个人主页报告引用的截图和真实读取结果 | 原地保留，哈希核对 |
| `article-detail-design/background.png`、`search-design-assets/background.png`、`search-design-assets/avatar.jpg`（均位于 `.figma_bridge_agent/`） | 分别与正式 `home-background.png`、`default-avatar.jpg` 逐字节相同，源码不引用导出路径 | 仅这 3 份重复副本归档后清理 |
| 其余设计导出、独有截图、`profile-header.svg` 与渲染图 | 设计参考、阶段视觉证据；不能仅因没有代码导入认定无用 | 本轮原地保留 |
| `fronted/test-results/` | 最近一次测试状态为 failed，包含详情页历史前进及图片增高的失败证据 | 原地保留；本轮不重跑浏览器测试，不声称问题已解决 |
| `artifacts/performance/20261116-112942-58e6d39f/`，3 文件 | 初始化失败产物，未进入正式分析清单 | 归档后清理 |
| `artifacts/performance/20261116-113032-b0cb146a/`，33 文件 | 短窗口烟测及复核，未进入正式三轮结论 | 归档后清理，不将烟测算入正式测量 |
| `artifacts/performance/20261116-113300-1b831540/`、`20261116-120704-560d41cc/` | [实测报告](../performance/resume-performance-2026-11-16.md)和分析清单直接依赖 | 完整原地保留，包括 failed 元数据、CSV、日志、源码快照和复核脚本 |
| `backend/src/test/.../performance/`、`infra/test/performance/`、性能报告附件及既有未提交简历资料 | 可复用测试和待交付成果 | 原地保留，不夹带进整理提交 |

四套快照共 1068 个普通文件：286 个与当前正式文件相同、294 个不同、486 个构建或测试生成文件、2 个仅快照存在的文件。最后两项均为旧 `src/editor/useEditorRecovery.ts`，分别存在于 banner/like 快照；已归档，不恢复旧会话草稿行为。详细路径和差异分类见外部比较清单，不能将这些快照笼统描述为全部重复文件。

## 正式文件归属

| 原位置 | 整理后位置与用途 |
|---|---|
| `backend/examples/vite.config.ts` | 移除；开发入口直接使用 `fronted/vite.config.ts` |
| `backend/examples/Caddyfile`、`nginx.conf.template` | `infra/deploy/web/`：站点入口代理和 SPA 静态托管模板 |
| `backend/examples/media/compose.dev.yaml`、`Caddyfile.dev`、`minio-*.json` | `infra/dev/media/`：历史独立上传联调环境和 MinIO 配置模板 |
| `backend/examples/media/cos-public-policy.json`、`cos-cors.xml` | `infra/deploy/media/`：生产存储配置模板 |
| `backend/examples/media/verify-cos.mjs` | `infra/test/media/verify-cos.mjs`：显式真实 COS 验证 |
| `backend/examples/media/upload.mjs`、`upload.test.mjs` | `infra/test/media/support/`：上传协议辅助函数和配套离线测试 |
| `backend/docs/public-read-cache.md` | `docs/public/guides/cache/zh-CN/public-read-cache.md`：当前公开缓存说明 |
| `infra/test/collect-resume-like.ps1` | 移除兼容转发；命令统一到 `infra/test/performance/run.ps1 -Suite Like` |

旧过程文档正文保留编写时结论。历史路径需要更新时仅修正入口，并明确历史运行时间；不将旧验收次数解释为本轮结果。前端 README 的长篇历史记录迁入开发报告，保留设计来源、限制和旧失败记录。

## 后续存放约定

- 正式源码、测试夹具、页面素材按实际职责保留，不按“示例”字样批量删除。
- 可复用的开发、部署和验证脚本分别进入 `infra/dev`、`infra/deploy`、`infra/test`，前端专用联调脚本继续放 `fronted/scripts`。
- Agent 一次性输出放已忽略的 `artifacts/agent/<任务>/`；工具会话继续使用工具自身目录。
- coverage 仅存覆盖率结果；基线优先引用 Git 提交，存在未提交差异时做有清单的外部归档。
- 正式性能原始产物继续放 `artifacts/performance`，精选报告及附件进入 `docs/development/reports/performance`。
- 历史过程文档保留原路径，在开发资料索引中标记状态及替代关系。

## 本轮验证

待目录迁移及清理完成后记录：归档及保护哈希、引用与链接、脚本语法和模块导入、Compose 挂载、上传协议测试、前端类型检查及构建、后端测试编译。不会执行真实 COS 写入、完整压测或覆盖浏览器失败证据。
