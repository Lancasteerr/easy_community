# 社区压测入口

[压测报告索引与历史证据](../../../docs/development/reports/performance/README.md)

## k6 日常基线

从仓库根目录使用 PowerShell 7、Java 25、Maven、Docker Compose 和 k6 2.1.0。

```powershell
./infra/test/performance/run.ps1 -Baseline Smoke
./infra/test/performance/run.ps1 -Baseline Standard -Scenario Search
./infra/test/performance/run.ps1 -Baseline Standard
./infra/test/performance/run.ps1 -Baseline Exploration -Scenario Like
```

`-Scenario` 可取 `All`、`Search`、`Detail`、`Like`、`Mixed`，默认 `All`。Smoke 使用 32 账号、96 篇文章、384 条点赞关系。正式基线固定种子，导入 1 万账号、5 万篇文章、50 万条关系并核验 ES 和 SQL，运行三轮。每档预热 60 秒、测量 5 分钟；冷详情有独立缓存命名空间，直接采首分钟。等待自然 PV 刷盘或异步点赞排空最多 120 秒。全量正式基线的测量和预热窗口本身约 4 小时，另计导入、启动和排空。

- 搜索和详情为 50／100／200 RPS，搜索正式档用默认 20 条分页。点赞分散和单篇热点为 60／120 RPS，探索档为 240 RPS。混合旅程为 50／100／200 VU，游客占 30%。登录与 CSRF 在窗口外完成。混合保留每账号 120 请求／分钟、60 点赞／分钟；独立详情放宽认证请求上限，点赞容量档放宽两种上限。配置逐档保存。
- 单 JVM 应用与独立 k6 进程同机。数据库和 ES 索引为本次随机生成，Redis 和 MQ 按档隔离。每档还原上档修改的点赞与 PV 数据，复用已验证的种子和 ES 索引。rank 定时任务停用，PV 定时刷盘及点赞消费照常启用。
- HTTP 保留客户端起止、状态和校验结果。RabbitMQ 管理 API 按分区采样，计数有刷新延迟，排空是采样观察时间。积压趋势把稳态样本分成三段，均值逐段升高且首尾相差至少 10 条时标记持续增长。SQL 锁观测包含 InnoDB 实例增量和专用库当前阻塞。覆盖时序按最近 32 个触达关系有界采样，最终对账覆盖全部受理关系与受影响计数。PV 核对本档预热和测量期间的全部详情 200，搜索档核验零 PV。
- 全部产物都写入 `artifacts/performance/<run-id>/`。先看 `report.md`，详细口径和现场数据见 `summary.json`、`seed.json`、`metadata.json` 及阶段目录中的 `telemetry.jsonl`、k6 汇总、压缩原始指标与请求日志。预热和测量分文件保存，Maven 构建与开发目录隔离。HTTP 请求日志只含固定测试标识、状态与时间，临时会话文件在运行后删除。

```powershell
node --test infra/test/performance/k6/workload.test.mjs
mvn -f backend/pom.xml -Dtest=BaselineToolTests test
```

## Java 历史对照及复现

本目录统一管理性能环境、运行和汇总入口。业务代码不因基准改动；测试专用HTTP路由只存在于 `backend/src/test` 且受 `resume-perf` profile 限制，不进入生产JAR。既有功能/故障测试入口仍留在 `infra/test`，不移动以免破坏运维文档链接。

## 前提与运行

Windows PowerShell 7、Java 25、Maven、Docker Desktop Linux Engine 与 Docker Compose 在 PATH。命令从仓库根目录执行；四个端口17906、17979、17972、19300及管理端口17973须可用。首次自动生成被Git忽略的 `backend/.env.resume-perf` 专用凭证。

```powershell
# 先验证完整链路，3秒样本仅用于烟测，不用于简历。
./infra/test/performance/run.ps1 -Seconds 3 -Rounds 1
# 正式多轮观测，默认每阶段60秒、三轮，全部场景约30分钟以上。
./infra/test/performance/run.ps1 -Seconds 60 -Rounds 3
# 单独复测读取/PV/索引，或点赞；相同专用环境必须顺序运行。
./infra/test/performance/run.ps1 -Suite Core -Seconds 60 -Rounds 3
./infra/test/performance/run.ps1 -Suite Like -Seconds 60 -Rounds 3 -LikeRates '60,120'
# 需要更长稳态窗口时扩至300秒；时间与样本量会同比增加。
./infra/test/performance/run.ps1 -Seconds 300 -Rounds 3 -LikeRates '30,60'
```

程序在 `artifacts/performance/<时间>-<随机ID>/` 持久保存结果，Maven clean不会删除。该目录被Git忽略；精选的脱敏报告另存 `docs/development/reports/performance`。同一入口使用文件锁防止并行运行，但手动启动其他连接本专用库的测试仍应避免。

## 环境边界

- Compose项目 `easy-community-resume-perf`，数据库 `easy_community_resume_test`，独立账号/Redis随机前缀/MQ命名空间/ES随机索引；不停止或清理开发依赖，不调用FLUSHDB。
- MySQL使用Docker命名持久卷，不使用tmpfs；这仍是Docker Desktop虚拟磁盘，不等同生产物理磁盘。测试报告记录 `innodb_flush_log_at_trx_commit` 与 `sync_binlog`。
- MySQL 2 CPU/1.5GiB，Redis 1 CPU/256MiB，RabbitMQ 2 CPU/768MiB，ES 2 CPU/1.5GiB。每个测试JVM最大堆768MiB，未施加应用CPU限制；发压与服务同机，两个点赞应用共享一个测试JVM，不能称“每实例2核2G”。开发容器仍可能竞争宿主资源。
- rank定时任务在Core实验中关闭，索引Relay与消费保持真实运行，PV保留默认每60秒刷盘，阶段结束额外调用一次生产刷盘服务。点赞实验提高测试用户限流，不修改生产默认值。

## 场景与指标口径

| 场景 | 入口与数据 | 能说明什么 |
|---|---|---|
| cache_source / cache_cached | 测试HTTP，一页20篇、4作者，背景256篇正文4096字；相同序列化与作者装配，前者直接批量读主库，后者调用生产列表缓存装配 | 绕过文章缓存与使用缓存的路径成本；不是历史版本对照；均无ES发现、鉴权和点赞装配 |
| feed_http | 实际游客 `GET /api/articles/feed`，80 QPS | ES→文章缓存→作者/计数装配的真实公开接口延迟；不计PV |
| pv_sql / pv_redis | 测试HTTP，20篇均匀分布、120 QPS；同步基线每次事务更新日统计与总量，优化组调用真实Redis统计服务 | PV子流程的响应延迟和实际MyBatis写语句数，不冒充整个文章详情接口性能 |
| index_publish_http | 实际Session/CSRF文章发布接口，5 QPS；真实事务Outbox→Relay→MQ→ES | 发布HTTP延迟与异步ES写入完成观察延迟；不是搜索refresh后可见延迟 |
| like_uniform / like_hot | 实际Session/CSRF点赞接口，16用户、32篇、双应用实例，按60/120 QPS阶梯 | Broker确认受理延迟与采样待提交趋势，不能把202当成SQL提交 |
| like_backlog | 停止本轮两个应用的点赞消费者至多5秒，60 QPS写入，再恢复 | 已受理消息积压与恢复耗时，未重启共享依赖 |

Core对照预热后逐轮交替A/B、B/A、A/B；PV两组分别恢复累计基线，结束刷盘后以SQL增量核对成功请求数。MyBatis拦截器按Mapper语句统计调用，不把一次事务当成一条SQL；PV写语句数仅包括总量增量更新和日累计upsert，含最后刷盘。

Core采用固定到达率、最多128个在途请求；调度迟到超过100ms或并发名额不足记dropped，不无限补发。异常记status=0；所有已发请求均进入延迟分位数，不删除失败样本。实际QPS按完成数/实际窗口（含末尾在途完成时间）计算，成功QPS另外保存。每轮P95/P99用最近秩法；不要平均多个分位数冒充合并样本分位数。

点赞沿用既有每用户串行客户端，忙跳过单独记录，其QPS仍需结合实际达到率判断。每阶梯前排空上阶段待提交，增加250ms待提交数量/最老年龄采样；采样误差单独标记。该集合由已确认HTTP命令与SQL版本对照生成，**不是RabbitMQ整个队列深度**；高版本可覆盖低版本，不证明每次点击分别落库。默认参数的两个60秒阶梯也不足以称生产长稳态容量。

索引完成以请求开始到ES实时GET观察到版本为准，每50ms轮询，含观察开销，原始样本单独保存；ES首次建档后的分数更新可能仍在后台继续。不能将该分位数与发布HTTP分位数混用。

## 结果与复核

每次运行保存：

- `metadata.json`：Git提交、工作区状态、运行时间、依赖镜像/资源限制/卷、每轮执行状态。
- `sources/`：当时基准脚本与Java测试源码，方便复核未提交工作区。
- `round-N/core.json`、`like-capacity.json`：每阶段全部汇总、SQL调用与一致性结果。
- 各场景CSV：逐请求延迟、状态及调度迟到；点赞原始CSV仅有延迟，状态分类见JSON。
- `index-observed.csv`：异步ES写入完成观察值；`core.log`、`like.log`及对应JUnit报告用于诊断。
- `summary.md`、`summary.csv`、`paired-comparisons.json`：按轮展开的表格及配对降幅。
- `verification.json`：从原始CSV独立重算P50/P95/P99、核对计划/完成/状态数量与一致性结果；不将读取汇总JSON本身当成复核。

```powershell
# 对已保存的运行重新汇总（将路径替换成真实运行目录）。
./infra/test/performance/summarize.ps1 -RunPath './artifacts/performance/<运行目录>'
./infra/test/performance/verify.ps1 -RunPath './artifacts/performance/<运行目录>'
# 诊断中断运行：只复核已完成阶段，输出runComplete=false和缺失报告，不冒充完整通过。
./infra/test/performance/verify.ps1 -RunPath './artifacts/performance/<中断运行目录>' -AllowPartial
# 停止本专用环境，保留持久数据卷供复核。
docker compose --env-file backend/.env.resume-perf -f infra/test/performance/compose.yaml stop
```

`measured`只表示测试实际执行并完成约定一致性校验，不代表所有负载达到SLA。引用性能数据前检查：目标/实际QPS、成功/完成、dropped、P95/P99、待提交趋势、采样错误及对账；失败轮不得静默丢弃。建议简历采用三轮中最差P95/P99与最低实际QPS，降幅采用逐轮配对中位数并保留范围。只有提高负载直至出现失败并细化阶梯后才能谈容量上限。

日志保留在本机，发布前需检查脱敏；不要提交凭证文件或完整Docker配置。60秒阶段结果可作为公开条件下的重复观测，投产前另做300秒以上稳态及故障/磁盘压力实验。

## 点赞新版与历史结果对比

```powershell
# 短烟测只验证采集和对账，不能作为性能结论；积压阶段仍固定300请求。
./infra/test/performance/run.ps1 -Suite Like -CompareLikeHistory -Seconds 3 -Rounds 1
# 正式测试：3轮，每阶段60秒。只测新版，历史目录只读。
./infra/test/performance/run.ps1 -Suite Like -CompareLikeHistory -Seconds 60 -Rounds 3 -LikeRates '60,120'
```

主对比沿用16用户、32文章、每用户一个在途请求，依次轮转文章60/120、单文章热点60/120、300请求积压恢复，最后追加240 QPS新版探索。uniform实际为每16次请求轮换文章，不是真正独立均匀随机。保留旧预热位置，不额外预热。两个应用上下文与客户端共享一个768MiB测试JVM。

新模式先在非Web上下文初始化随机命名空间，再启动两实例；只注册SQL计数拦截器，不注册其他性能HTTP路由。每阶段保留旧acceptedPending及250ms版本覆盖采样，同时每秒读取Broker异步管理统计。物理排空必须满足SQL覆盖完成、ready/unacked为零及累计ACK达到受理数；管理统计默认约5秒刷新，物理排空耗时不能与历史SQL覆盖耗时混算。

历史清单为20261116-113300-1b831540的round-1/2及20261116-120704-560d41cc的round-1。前一运行整体中断，但两个完整点赞轮次独立通过校验。baseline-manifest.json保存选中输入和SHA256；sources及source-hashes.json保存本次源码快照。不会修改历史verification.json或其他产物。

新版CSV新增调度/起止时刻、状态、逻辑用户与文章编号、目标状态和版本；coverage.csv独立记录首次版本覆盖观察。逐阶段JSON还记录Broker序列、两实例指标差、SQL语句调用次数、组提交与合并数量。like-verification.json从原始样本复核数量、分位数、覆盖和SQL/聚合计数，不只读取汇总结果自证。

historical-comparison.json/md及PNG/SVG比较各轮中位数与范围，不做强行配对。实际成功QPS中位数相差超过5%或存在错误/采样缺口时，不给单一延迟变化比例；缺失的历史MQ/SQL次数不反推。240 QPS不代表持续容量上限。

成功轮次清理本轮临时数据；失败保留随机命名空间数据与部分JSON/CSV，120秒排空超时停止后续阶段。两种情况都会关闭本次应用，新模式还停止本次启动的专用容器、保留卷，原有开发服务保持原状。
