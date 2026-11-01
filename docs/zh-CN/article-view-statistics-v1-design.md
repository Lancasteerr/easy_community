# 文章浏览量统计 V1

> 已实施。更新日期：2026-11-02。以 Redis 每日 Hash 与实时总量 Hash 原子计数，SQL 按日差值刷盘。

## 1. 口径与接口

普通详情成功取得可见内容后尝试记录一次 PV。游客、登录用户、作者本人、重复刷新同样计数；列表、编辑读取、隐藏或不存在详情不计数。计数在普通详情入口统一执行，位于缓存重试循环之外。

直接扩展现有 `ArticleDetailView` 与 `ArticleListView`，增加数字字段 `viewCount`，不新增管理或其他展示 DTO。管理列表与后续公共列表共用列表 DTO，展示取舍交给前端。编辑 DTO 不增加统计字段，本期不新增公共列表接口。

详情正常返回包含本次浏览后的实时总量；列表只读取总量，不增加 PV。统计 Redis 失败时使用 SQL 已落库总量，SQL 也失败则返回现有依赖错误，不能伪造为 0。正文缓存自身故障仍遵守原 CRUD 读取契约。

## 2. SQL 与 Redis 数据

Flyway V4 新增 `article.view_count`，非负 BIGINT、默认 0；新增 `article_view_stat(article_id, stat_date, pv)`，以文章 ID、日期为联合主键，日值非负，外键关联文章。物理删除时级联删除日统计；正常业务删除仍只是隐藏。旧文章从 0 开始。

正文实体、内容快照不引入统计字段；正文编辑继续显式更新原有字段。统计 UPDATE 仅修改 view_count，不修改 updated_at、正文或 hidden。

定义 S 为 `${app.auth.redis-prefix}:article:stats:v1`，实际 Key 保留包围命名空间的花括号：

| 数据 | Key 示例（开发环境） | 字段和值 | 过期 |
|---|---|---|---|
| 日累计 | `{ec:dev:article:stats:v1}:pv:20261102` | 文章 ID → 当日累计 PV | 首次设置两天，访问不续期 |
| 实时总量 | `{ec:dev:article:stats:v1}:total` | 文章 ID → 总 PV | 无 TTL |

两个 Key 使用同一 hash tag，支持多 Key Lua 同槽执行；因此统计会集中于同一槽，本期不做分片。命名空间独立于正文缓存版本，切换 ARTICLE_CACHE_VERSION 或重建墓碑不清理统计。

统计日期复用现有 Clock，并显式转换为 Asia/Shanghai，不依赖宿主机时区。

## 3. 计数、初始化与装配

`article-views.lua` 提供 COUNT、INITIALIZE、REPAIR 三种操作，先检查相关 Key 类型、计数格式及可预见的溢出错误再写入。Redis HINCRBY 使用整数运算；Lua 比较整数使用十进制字符串，返回 HGET 字符串，避免浮点转换损失 BIGINT 精度。

### 详情

1. 内容及可见性确认后，调用 COUNT。总量存在时，同时将日字段与总量字段各加一，并在日 Key 没有 TTL 时设置两天 TTL。
2. 总量缺失且未提供基线时，明确返回 NEEDS_BASELINE，不写入任何计数。
3. 独立 SQL Reader 查询已落库总量并结束事务，随后携带基线调用 COUNT。Lua 仅在总量仍缺失时初始化，保留其他请求已经建立或增加的值，再完成两处加一。
4. 返回更新后的总量，生成带 viewCount 的原 DTO 副本。

仅确认未写入的 NEEDS_BASELINE 可进入第二次 COUNT；超时或执行结果未知时不重发加一，记录日志并回退 SQL 总量。

### 列表

内容装配后对最终返回 ID 执行一次 HMGET。缺失项通过一次批量 SQL 查询取得基线，再以 Pipeline 执行 INITIALIZE Lua：只初始化仍缺失的字段，返回并发初始化或增长后的实际值。初始化不写入日累计，也不增加总量。

Redis 读取失败则批量回退 SQL；初始化失败时使用已取得的 SQL 基线。空列表不访问统计依赖。

### 正文缓存

沿用现有详情、列表内容投影。缓存发布时显式排除 viewCount；旧缓存缺少该字段可直接读取，null 仅表示内容 DTO 尚未装配统计。成功 HTTP 响应必须装配数字总量。统计变化不失效正文缓存，编辑读取不增加计数。

## 4. 每分钟刷盘

仅正常 Web 服务调度，启动延迟一分钟，之后每轮完成再等待一分钟。每轮固定业务日期，依次处理昨天、今天，各读取一次完整 HGETALL。单日期失败记录日志，仍处理另一日期；非法 Hash 项记录日志并跳过。

```text
Redis HGETALL（日累计快照，SQL 事务外）
    ↓
独立 SQL Service（每日期一笔 REPEATABLE_READ 事务）
    ↓
事务代理成功返回日值修复项及 SQL 总量下限
    ↓
Pipeline + Redis Lua 修复（SQL 事务外）
```

事务按文章 ID 排序，使用 selectPvForUpdate 读取最新日值。设 Redis 快照日值为 R，SQL 日值为 P（缺行按 0）：

| 条件 | SQL 操作 | 提交后修复 |
|---|---|---|
| R > P | 总量增加 R−P，日统计 UPSERT 为 R | 校准实时总量下限 |
| R = P | 不重复累计 | 校准实时总量下限 |
| R < P | 不修改 SQL | 提高日字段下限，并校准实时总量下限 |

总量 UPDATE 影响 0 行时跳过该文章，不插入无主日统计。隐藏文章可继续刷入此前浏览。日值和总量同事务提交或回滚；首次建行竞争可能死锁，回滚后留给下一轮，不在本轮自动重试。

SQL Service 不依赖 Redis，不通过事务回调访问 Redis。Redis Store 和无事务编排使用 Propagation.NEVER 约束调用边界。SQL 提交失败时不修复；提交后修复失败不重放 SQL。刷盘成功后不删除或清零 Hash。

## 5. 修复与误差边界

修复 Lua 同时持有日 Key 和总量 Key：日字段仍存在且低于 SQL 日值时提高到该日值；字段消失则跳过，不重建。实时总量低于 SQL 总量时提高到 SQL 总量，缺失时初始化；已存在的更高值保留。

日修复差额和 SQL 刷盘差值都不能再次加入 Redis 总量，因为对应浏览已经在 COUNT 中计入。修复仅设置下限，不执行差额累加。

总量丢失后只使用 SQL 基线恢复，尚未刷盘部分可能暂时少展示，后续刷盘再提高下限。Redis 数据丢失及修复期间允许少计，不引入跨存储锁或额外恢复协议。停止刷盘超过日 Hash 保留窗口可能丢失未落库浏览。

本期不包含 ZSET、榜单、七日汇总、推荐分、ES、任务选主、统计保留期清理或分片刷盘。完整日 Hash 与单日期事务的成本随活跃文章数增长。

## 6. 实施与验证

按三个功能提交组织：

1. `feat(article): 增加文章浏览统计持久化`：迁移、Mapper、日级事务、SQL 基线查询。
2. `feat(article): 增加实时浏览总量与每日原子计数`：双 Hash Lua、初始化、周期刷盘、提交后修复。
3. `feat(article): 展示实时文章浏览量`：扩展现有 DTO、实时装配、SQL 降级、正文缓存兼容与文档。

验证覆盖详情各缓存分支只计一次、列表及编辑不计数、并发初始化与双 Hash 增长、跨日及 TTL、大整数与异常预检、差值幂等、真实事务回滚与并发首次建行、低值修复、统计故障降级、Redis 不处于 SQL 事务中，以及编辑不覆盖统计。

常规测试执行 `mvn test`。真实服务测试沿用 IT_ARTICLE、IT_REDIS、IT_DB_URL、IT_DB_USERNAME/PASSWORD 和 IT_REDIS_HOST/PORT/PASSWORD，仅使用专用 easy_community_*test* 数据库及随机前缀；凭证不写入文档或提交。Windows 按现有约定添加 `-DargLine=-Djdk.net.unixdomain.tmpdir=E:/temp`。完整验收执行 `mvn clean verify`，跳过项单独记录。

HTTP 和原有缓存约定继续以 [CRUD 接入文档](article-crud-implementation.md)为准。

### 本次验收记录（2026-11-02）

使用专用 easy_community_article_test 库和真实 Redis，启用 IT_ARTICLE、IT_REDIS 及测试数据库凭证后执行 `mvn '-DargLine=-Djdk.net.unixdomain.tmpdir=E:/temp' clean verify`，构建及可执行 JAR 打包成功：183 项测试，173 项通过、0 失败、0 错误；10 项既有 MediaV2IntegrationTests 因未启用真实对象存储联调而跳过，未计入通过数。文章模块 70 项全部通过，本次新增 31 项测试。

实测覆盖双 Hash 并发计数与初始化、日 TTL 不续期、跨日、大整数和异常预检、差值幂等、真实 MySQL 整日期回滚和并发首次建行冲突后重试、低值修复不覆盖增长、统计失败 SQL 降级、详情与列表 HTTP 字段，以及正文缓存不保存总量。Redis Store 的事务代理拒绝 SQL 事务内调用，贯通测试同时断言计数、批量读取、初始化、快照和修复调用均无活动 SQL 事务。
