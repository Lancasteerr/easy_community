# 开发过程资料与迁移清单

本目录保留计划、交接、审阅、被替代设计、阶段验收及实验原始数据，供开发协作与历史追溯。统一使用中文，按“文体 → 功能”组织，不设语言层级、不维护英文版本。

过程文档和报告继续保留在仓库内。2026-11-16 经维护者确认，对本地冗余中间产物执行先归档、校验再清理；范围和恢复方法见整理报告。正文保留编写时结论，日期和替代关系在本索引说明，不能把原稿中的“待实施”当成当前仓库状态。

## 当前阅读入口

- 压测数据、可能的性能瓶颈与建议见[压测结果汇总](loadtest/README.md)。
- 简历四条主亮点、三轮真实性能与脚本见[最终简历稿](reviews/resume/resume-final-2026-11-16.md)、[2026-11-16实测报告](reports/performance/resume-performance-2026-11-16.md)及[统一压测文档](../../infra/test/performance/README.md)。

- 当前功能说明以[公开文档](../public/README.md)为入口。
- 个人主页当前底色与连续收缩修正见[2026-11-15 收缩验收记录](reports/frontend/profile-collapse-validation-2026-11-15.md)；初次交付结果见[个人主页验收记录](reports/frontend/public-profile-validation-2026-11-15.md)，后端核对与取舍背景保留在[实施审查稿](plans/frontend/public-profile-plan-2026-11-15.md)。
- 文章标签 JSON 元数据、本地目录搜索与按版本校验的实施方案见[2026-11-12 后端计划](plans/article/article-tags-json-local-cache-plan.md)。
- 内容管理页的本轮前端验收及真实ES联调限制见[2026-11-11验收记录](reports/frontend/content-management-validation-2026-11-11.md)。
- 文章旧异步 CRUD V1 已被同步短事务 CRUD V2 取代，旧文件名中的 feed 不代表其内容属于当前推荐流。
- ES 早期审阅 → 补充复核 → 2026-11-08 实施计划依次收敛；后续已存在 2026-11-08 验收记录，原计划及交接中的“尚未实现／待实施”保留为历史状态。
- 媒体验收、点赞验收和包重构报告中的旧失败及后续修复按各自日期阅读，不改写当时结果。容量报告与 JSON 属于同一次历史实验，不能冒充当前版本复测。

## 资料状态与当前入口

“已完成”只表示对应实施阶段有交付记录，“历史证据”不表示当前版本复测通过；报告中未完成的真实联调和手动验收继续保留。

| 资料组 | 状态与替代关系 | 当前入口 |
|---|---|---|
| 公开架构、指南、设计与运维 | 当前有效，随代码维护 | [公开文档](../public/README.md) |
| 文章异步 V1 设计、原始交接 | 被替代；正文保留历史约束 | [CRUD V2](../public/designs/article/zh-CN/article-crud-v2-design.md) |
| 搜索早期审阅、补充复核、交接和实施计划 | 历史方案；已有搜索阶段验收，后续以公开运维说明为准 | [搜索运维](../public/operations/search/zh-CN/article-search-operations.md) |
| 标签实施计划、个人主页实施计划 | 对应阶段已交付；未完成的真实交互仍按报告记录 | [标签报告](reports/article/article-tags-validation-2026-11-12.md) · [主页报告](reports/frontend/public-profile-validation-2026-11-15.md) |
| 编辑页、内容管理、搜索页与个人主页阶段报告 | 历史证据；编辑器白色主题替代早期样式，旧草稿恢复行为已被当前身份边界取代；详情滚动失败未解决 | [前端当前说明](../../fronted/README.md) |
| 点赞交接与历史容量、媒体、搜索、包重构验收 | 历史证据；原始数据与报告一起保留 | [原始迁移清单](#全量迁移清单) |
| 简历准备计划与审阅稿 | 阶段性历史方案；实际测量范围以实测报告为准，未测项目不标为完成 | [性能使用文档](../../infra/test/performance/README.md) |
| Agent 中间产物去留及外部备份 | 当前维护记录；源目录清理不改变业务验收结论 | [整理报告与恢复方法](reports/maintenance/file-organization-2026-11-16.md) |

## 全量迁移清单

原有资料共 22 个文件：21 份 Markdown、1 份 JSON。以下同时列出公开文档，确保每个原路径都有明确去向；链接始终指向当前实际位置。

| 原路径（相对 docs） | 整理后路径／资料入口 | 用途、状态与替代关系 | 日期／版本 | 原 Git 状态 | 当前阅读入口 |
|---|---|---|---|---|---|
| `en-US/backend-architecture.md` | [public/architecture/backend/en-US/backend-architecture.md](../public/architecture/backend/en-US/backend-architecture.md) | 当前架构；与中文版对应维护 | 持续维护 | 已跟踪 | [阅读](../public/architecture/backend/en-US/backend-architecture.md) |
| `zh-CN/backend-architecture.md` | [public/architecture/backend/zh-CN/backend-architecture.md](../public/architecture/backend/zh-CN/backend-architecture.md) | 当前架构与代码组织约定 | 持续维护 | 已跟踪 | [阅读](../public/architecture/backend/zh-CN/backend-architecture.md) |
| `zh-CN/article-crud-v2-design.md` | [public/designs/article/zh-CN/article-crud-v2-design.md](../public/designs/article/zh-CN/article-crud-v2-design.md) | 当前有效设计；取代异步 CRUD V1 | 2026-11-01 | 已跟踪 | [阅读](../public/guides/article/zh-CN/article-crud-implementation.md) |
| `zh-CN/media-service-v2-design.md` | [public/designs/media/zh-CN/media-service-v2-design.md](../public/designs/media/zh-CN/media-service-v2-design.md) | 当前有效设计；保留 V2 实施背景 | 2026-10-29 | 已跟踪 | [阅读](../public/guides/media/zh-CN/media-service.md) |
| `zh-CN/article-crud-implementation.md` | [public/guides/article/zh-CN/article-crud-implementation.md](../public/guides/article/zh-CN/article-crud-implementation.md) | 当前接入与恢复契约；含历史验收章节 | 2026-11-01 | 已跟踪 | [阅读](../public/guides/article/zh-CN/article-crud-implementation.md) |
| `zh-CN/like-system-implementation.md` | [public/guides/like/zh-CN/like-system-implementation.md](../public/guides/like/zh-CN/like-system-implementation.md) | 当前接口与接入协议；含历史实施批次 | 持续维护 | 已跟踪 | [阅读](../public/operations/like/zh-CN/like-system-operations.md) |
| `zh-CN/media-service.md` | [public/guides/media/zh-CN/media-service.md](../public/guides/media/zh-CN/media-service.md) | 当前上传接入、配置和部署说明 | 持续维护 | 已跟踪 | [阅读](../public/guides/media/zh-CN/media-service.md) |
| `zh-CN/like-system-operations.md` | [public/operations/like/zh-CN/like-system-operations.md](../public/operations/like/zh-CN/like-system-operations.md) | 当前点赞部署、维护与故障恢复 | 持续维护 | 已跟踪 | [阅读](../public/operations/like/zh-CN/like-system-operations.md) |
| `zh-CN/article-search-operations.md` | [public/operations/search/zh-CN/article-search-operations.md](../public/operations/search/zh-CN/article-search-operations.md) | 当前搜索与推荐接口、配置及索引维护 | 持续维护 | 已跟踪 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-es-search-feed-v1-plan.md` | [development/plans/search/article-es-search-feed-v1-plan.md](plans/search/article-es-search-feed-v1-plan.md) | 历史实施计划；已有后续验收记录，原文“尚未实现”是编写时状态 | 2026-11-08 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/community_article_codex_handoff.md` | [development/handoffs/article/community_article_codex_handoff.md](handoffs/article/community_article_codex_handoff.md) | 原始交接来源；当前契约以 CRUD V2 和接入指南为准 | 2026-10-31 | 原未跟踪，随本次整理保留 | [阅读](../public/guides/article/zh-CN/article-crud-implementation.md) |
| `zh-CN/like_system_codex_handoff.md` | [development/handoffs/like/like_system_codex_handoff.md](handoffs/like/like_system_codex_handoff.md) | 定稿 V4 交接来源；日常接入和运维以公开文档为准 | V4，原文未标日期 | 已跟踪 | [阅读](../public/guides/like/zh-CN/like-system-implementation.md) |
| `zh-CN/社区系统_ES接入与可靠索引同步_Codex交接文档.md` | [development/handoffs/search/社区系统_ES接入与可靠索引同步_Codex交接文档.md](handoffs/search/社区系统_ES接入与可靠索引同步_Codex交接文档.md) | 目标设计来源；保留编写时“待实施”状态，不作为当前完成情况 | 2026-11-08 修订 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-es-review-2026-11-07.md` | [development/reviews/search/article-es-review-2026-11-07.md](reviews/search/article-es-review-2026-11-07.md) | 早期审阅稿；已被补充复核与后续计划收敛，不累计执行旧约束 | 2026-11-07 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-es-review-supplement-2026-11-07.md` | [development/reviews/search/article-es-review-supplement-2026-11-07.md](reviews/search/article-es-review-supplement-2026-11-07.md) | 阶段复核；替换此前审阅稿，排名策略继续由后续计划收敛 | 2026-11-08 修订 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-feed-v1-design.md` | [development/designs/article/article-feed-v1-design.md](designs/article/article-feed-v1-design.md) | 已被 CRUD V2 取代；实际为旧版异步 CRUD 设计，并非当前推荐流设计 | 2026-10-31 | 原未跟踪，随本次整理保留 | [阅读](../public/designs/article/zh-CN/article-crud-v2-design.md) |
| `zh-CN/backend-package-refactor-validation-2026-11-06.md` | [development/reports/backend/backend-package-refactor-validation-2026-11-06.md](reports/backend/backend-package-refactor-validation-2026-11-06.md) | 包重构验证与历史基线；媒体超时契约后续另行修正 | 2026-11-06 | 已跟踪 | [阅读](../public/architecture/backend/zh-CN/backend-architecture.md) |
| `zh-CN/media-service-v2-validation.md` | [development/reports/media/media-service-v2-validation.md](reports/media/media-service-v2-validation.md) | 阶段验收，含 2026-11-06 超时契约补充；保留原始范围 | 2026-10-29；2026-11-06 补充 | 已跟踪 | [阅读](../public/guides/media/zh-CN/media-service.md) |
| `zh-CN/article-search-validation-2026-11-08.md` | [development/reports/search/article-search-validation-2026-11-08.md](reports/search/article-search-validation-2026-11-08.md) | 搜索与推荐阶段验收；不表示当前版本已重新运行 | 2026-11-08 | 已跟踪 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/like-validation-2026-11-06.md` | [development/reports/like/like-validation-2026-11-06.md](reports/like/like-validation-2026-11-06.md) | 点赞阶段验收，区分历史实现与后续媒体修复 | 2026-11-06 | 已跟踪 | [阅读](../public/guides/like/zh-CN/like-system-implementation.md) |
| `zh-CN/like-capacity-2026-11-05.md` | [development/reports/like/like-capacity-2026-11-05.md](reports/like/like-capacity-2026-11-05.md) | 历史双实例容量实验；须结合当时环境和实现解读，与 JSON 一起保留 | 2026-11-05 | 已跟踪 | [阅读](../public/guides/like/zh-CN/like-system-implementation.md) |
| `zh-CN/like-capacity-2026-11-05.json` | [development/reports/like/like-capacity-2026-11-05.json](reports/like/like-capacity-2026-11-05.json) | 容量实验原始数据；逐字节保留，不单独挪走附件 | 2026-11-05 | 已跟踪 | [阅读](reports/like/like-capacity-2026-11-05.md) |

## 后续维护规则

- 新的过程资料直接放入对应文体和功能目录，使用中文；保留日期和版本以区分阶段记录。
- 生效后的接口、设计与运维约束应同步维护到公开文档，公开文档不依赖本目录链接或本机绝对路径。
- 混合文档本轮按主用途迁移，不抽离历史章节；公开文档中的相关章节已标注历史性质。
- 原始 JSON 与对应报告共同保留；需要重测时另存新结果，不覆盖旧数据。

- Agent 一次性输出使用已忽略的 `artifacts/agent/<任务>/`，工具会话留在工具目录；coverage 不存源码快照。
- 清理中间产物前先检查运行依赖、文档引用和独有内容；历史性能失败运行可能仍含有效测量，不能按状态批量删除。
