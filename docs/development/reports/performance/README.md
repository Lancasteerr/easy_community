# 压测报告索引

新基线的逐轮报告和原始数据保存在本机 `artifacts/performance/<run-id>/`。引用前核对代码版本、负载模式、数据规模、应用与依赖配置、计时边界和对账状态。条件一致的运行才计算回归差异。

| 日期 | 报告与附件 | 使用边界 |
|---|---|---|
| 2026-11-05 | [点赞双实例容量实验](../like/like-capacity-2026-11-05.md) · [原始 JSON](../like/like-capacity-2026-11-05.json) | 16 用户、32 篇文章，tmpfs、30 秒阶段。保留热点与积压观察。 |
| 2026-11-16 | [综合实测](resume-performance-2026-11-16.md) · [当时的结果汇总](../loadtest/README.md) · [分析清单](2026-11-16/analysis-manifest.json) · [逐项 CSV](2026-11-16/measurements.csv) | 两次运行拼齐三轮，首批整体中断，第三轮点赞另行补齐。 |
| 2026-11-18 | [点赞新旧对照](2026-11-18/like-history-comparison.md) · [JSON](2026-11-18/like-history-comparison.json) · [诊断 CSV](2026-11-18/like-new-diagnostics.csv) · [热点时序](2026-11-18/like-hot-timeseries.svg) | 三轮正式对照。240 RPS 是探索档，保留热点积压与恢复时序。 |
| 日期未知 | [搜索顺序观测](history/search-sequential-observation.json) | 从本机 `backend/target/search-performance.json` 原样归档。预热、冷读、后台任务下分别顺序发出 30／10／30 次请求，另有一次刷盘与编辑观测。提交与环境未知。SHA-256 为 `403d9b35a0a8923b86b6bdc933348eb31df2a6892bb8393af00041008b526b81`。 |

| 原始运行 ID | 用途 | 完成状态与关联报告 |
|---|---|---|
| `20261116-113300-1b831540` | 综合首批 | 整体中断，已完成阶段通过部分复核。综合报告取三轮 Core 和前两轮 Like。 |
| `20261116-120704-560d41cc` | 点赞补测 | 完整，补齐综合报告第三轮 Like。 |
| `20261118-021926-3d18f406` | 新版点赞 3 秒烟测 | 完整，用于验证采集和对账。 |
| `20261118-022300-cde9970a` | 新版点赞三轮正式对照 | 完整，支撑 11 月 18 日报告。 |

各原始目录留在本机，保留运行 ID、逐请求数据、日志和复核记录。运行方式见[统一压测入口](../../../../infra/test/performance/README.md)。
