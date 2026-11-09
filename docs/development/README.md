# 开发过程资料与迁移清单

本目录保留计划、交接、审阅、被替代设计、阶段验收及实验原始数据，供开发协作与历史追溯。统一使用中文，按“文体 → 功能”组织，不设语言层级、不维护英文版本。

资料目前保留在仓库内，后续可整体迁出；本次不删除任何文件，迁出及删除由维护者亲自确认和操作。正文保留编写时结论，日期和替代关系在本索引说明，不能把原稿中的“待实施”当成当前仓库状态。

## 当前阅读入口

- 当前功能说明以[公开文档](../public/README.md)为入口。
- 文章旧异步 CRUD V1 已被同步短事务 CRUD V2 取代，旧文件名中的 feed 不代表其内容属于当前推荐流。
- ES 早期审阅 → 补充复核 → 2026-11-08 实施计划依次收敛；后续已存在 2026-11-08 验收记录，原计划及交接中的“尚未实现／待实施”保留为历史状态。
- 媒体验收、点赞验收和包重构报告中的旧失败及后续修复按各自日期阅读，不改写当时结果。容量报告与 JSON 属于同一次历史实验，不能冒充当前版本复测。

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
| `zh-CN/article-es-search-feed-v1-plan.md` | [development/plans/search/article-es-search-feed-v1-plan.md](../zh-CN/article-es-search-feed-v1-plan.md) | 历史实施计划；已有后续验收记录，原文“尚未实现”是编写时状态 | 2026-11-08 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/community_article_codex_handoff.md` | [development/handoffs/article/community_article_codex_handoff.md](../zh-CN/community_article_codex_handoff.md) | 原始交接来源；当前契约以 CRUD V2 和接入指南为准 | 2026-10-31 | 原未跟踪，随本次整理保留 | [阅读](../public/guides/article/zh-CN/article-crud-implementation.md) |
| `zh-CN/like_system_codex_handoff.md` | [development/handoffs/like/like_system_codex_handoff.md](../zh-CN/like_system_codex_handoff.md) | 定稿 V4 交接来源；日常接入和运维以公开文档为准 | V4，原文未标日期 | 已跟踪 | [阅读](../public/guides/like/zh-CN/like-system-implementation.md) |
| `zh-CN/社区系统_ES接入与可靠索引同步_Codex交接文档.md` | [development/handoffs/search/社区系统_ES接入与可靠索引同步_Codex交接文档.md](../zh-CN/社区系统_ES接入与可靠索引同步_Codex交接文档.md) | 目标设计来源；保留编写时“待实施”状态，不作为当前完成情况 | 2026-11-08 修订 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-es-review-2026-11-07.md` | [development/reviews/search/article-es-review-2026-11-07.md](../zh-CN/article-es-review-2026-11-07.md) | 早期审阅稿；已被补充复核与后续计划收敛，不累计执行旧约束 | 2026-11-07 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-es-review-supplement-2026-11-07.md` | [development/reviews/search/article-es-review-supplement-2026-11-07.md](../zh-CN/article-es-review-supplement-2026-11-07.md) | 阶段复核；替换此前审阅稿，排名策略继续由后续计划收敛 | 2026-11-08 修订 | 原未跟踪，随本次整理保留 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/article-feed-v1-design.md` | [development/designs/article/article-feed-v1-design.md](../zh-CN/article-feed-v1-design.md) | 已被 CRUD V2 取代；实际为旧版异步 CRUD 设计，并非当前推荐流设计 | 2026-10-31 | 原未跟踪，随本次整理保留 | [阅读](../public/designs/article/zh-CN/article-crud-v2-design.md) |
| `zh-CN/backend-package-refactor-validation-2026-11-06.md` | [development/reports/backend/backend-package-refactor-validation-2026-11-06.md](../zh-CN/backend-package-refactor-validation-2026-11-06.md) | 包重构验证与历史基线；媒体超时契约后续另行修正 | 2026-11-06 | 已跟踪 | [阅读](../public/architecture/backend/zh-CN/backend-architecture.md) |
| `zh-CN/media-service-v2-validation.md` | [development/reports/media/media-service-v2-validation.md](../zh-CN/media-service-v2-validation.md) | 阶段验收，含 2026-11-06 超时契约补充；保留原始范围 | 2026-10-29；2026-11-06 补充 | 已跟踪 | [阅读](../public/guides/media/zh-CN/media-service.md) |
| `zh-CN/article-search-validation-2026-11-08.md` | [development/reports/search/article-search-validation-2026-11-08.md](../zh-CN/article-search-validation-2026-11-08.md) | 搜索与推荐阶段验收；不表示当前版本已重新运行 | 2026-11-08 | 已跟踪 | [阅读](../public/operations/search/zh-CN/article-search-operations.md) |
| `zh-CN/like-validation-2026-11-06.md` | [development/reports/like/like-validation-2026-11-06.md](../zh-CN/like-validation-2026-11-06.md) | 点赞阶段验收，区分历史实现与后续媒体修复 | 2026-11-06 | 已跟踪 | [阅读](../public/guides/like/zh-CN/like-system-implementation.md) |
| `zh-CN/like-capacity-2026-11-05.md` | [development/reports/like/like-capacity-2026-11-05.md](../zh-CN/like-capacity-2026-11-05.md) | 历史双实例容量实验；须结合当时环境和实现解读，与 JSON 一起保留 | 2026-11-05 | 已跟踪 | [阅读](../public/guides/like/zh-CN/like-system-implementation.md) |
| `zh-CN/like-capacity-2026-11-05.json` | [development/reports/like/like-capacity-2026-11-05.json](../zh-CN/like-capacity-2026-11-05.json) | 容量实验原始数据；逐字节保留，不单独挪走附件 | 2026-11-05 | 已跟踪 | [阅读](../zh-CN/like-capacity-2026-11-05.md) |

## 后续维护规则

- 新的过程资料直接放入对应文体和功能目录，使用中文；保留日期和版本以区分阶段记录。
- 生效后的接口、设计与运维约束应同步维护到公开文档，公开文档不依赖本目录链接或本机绝对路径。
- 混合文档本轮按主用途迁移，不抽离历史章节；公开文档中的相关章节已标注历史性质。
- 原始 JSON 与对应报告共同保留；需要重测时另存新结果，不覆盖旧数据。
