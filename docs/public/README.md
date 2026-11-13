# 公开文档

供仓库使用者阅读的当前架构、有效设计、接入契约和运维说明。目录按“文体 → 功能 → 语言”组织；仅维护已有语言版本。

| 功能 | 阅读入口 |
|---|---|
| 后端整体 | [中文架构](architecture/backend/zh-CN/backend-architecture.md) · [English architecture](architecture/backend/en-US/backend-architecture.md) |
| 用户资料 | [简介、文字编辑与共享缓存](guides/user/zh-CN/user-profile.md) |
| 文章 CRUD | [接入与缓存恢复](guides/article/zh-CN/article-crud-implementation.md) · [V2 设计](designs/article/zh-CN/article-crud-v2-design.md) |
| 文章标签 | [JSON 存储、本地目录与版本校验](guides/article/zh-CN/article-tags.md) |
| 搜索与推荐 | [接口、配置与索引维护](operations/search/zh-CN/article-search-operations.md) |
| 点赞 | [接口与接入](guides/like/zh-CN/like-system-implementation.md) · [部署与故障处置](operations/like/zh-CN/like-system-operations.md) |
| 媒体 | [上传接入与配置](guides/media/zh-CN/media-service.md) · [V2 设计](designs/media/zh-CN/media-service-v2-design.md) |

本地启动、环境配置与后端验证见[后端 README](../../backend/README.md)。

按主用途保留的混合文档中，历史实施批次和验收章节仅用于说明背景；验证命令与适用条件仍需结合当前版本及目标环境使用。公开指南不将历史容量实验作为生产 SLA，也不将跳过的测试计入通过。
