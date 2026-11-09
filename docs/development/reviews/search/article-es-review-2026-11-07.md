# 社区系统 ES 接入与可靠索引同步：代码审阅与实施定稿

审阅日期：2026-11-07。审阅依据是当前 master 工作区与《社区系统_ES接入与可靠索引同步_Codex交接文档.md》。交接文档被视为待核验的目标设计，其中的实施顺序、验收项和示例不是已完成事实。本次只新增审阅文档，未修改业务代码、迁移数据库、声明 MQ 拓扑或启动 ES。

## 1. 审阅结论

保留 MySQL 权威数据、事务 Outbox、RabbitMQ 至少一次交接、ES 派生索引、Redis/MySQL 组装卡片的总体方案。交接文档已经正确指出 GET 的 TOCTOU、元数据一致快照、版本墓碑、局部更新和分页游标问题；这些不是文档完全遗漏的问题，实施时必须把约束变成代码与集成测试。

首版收敛为以下决策：

- ES GET 版本预检查默认关闭，可配置开启；正确性始终由 ES 内的业务版本条件脚本保证。
- Outbox 保留六字段，不加状态、重试次数、消息历史或独立消费者去重表。
- 专用索引工作队列和死信队列；同一工作消费者负责 ES 更新与文章双缓存失效，两项完成后才 ACK，首版不再增加缓存失效队列。
- 元数据与分数分别比较业务版本、分别修改字段，禁止全文档覆盖。
- 公开搜索/信息流增加主库批量可见性检查，明确每批多一次轻量 SQL；缓存只负责卡片内容，不承担最新权限判断。
- 推荐分先只读 SQL 已落库统计：七个业务自然日浏览量 + 当前累计点赞数。每五分钟分段全量重算，先不合并 Redis 增量、不维护活跃集、不增加七日点赞表。
- 排名上线时增加一行持久计数器，为每个 SQL 统计快照批次生成 scoreVersion；它不是任务状态机，不保存失败明细或历史。
- 初次导入使用增量消费者与条件回填；首版重建允许维护窗口。零停机双写重建作为后续独立能力。

## 2. 当前代码与目标设计的差异

以下路径均相对仓库根目录。

| 能力 | 当前真实实现 | 本次需要补充或调整 |
|---|---|---|
| 技术栈 | backend/pom.xml：Boot 4.1.1、Java 25、MyBatis-Plus 3.5.17；本机该 Boot BOM 管理 Spring AMQP 4.1.1、ES Java Client 9.4.5 | 尚未引入 ES 依赖；实施时沿用 BOM，并在专用环境验证服务器匹配，不另加 Spring Data Repository 抽象 |
| 运行环境 | infra/test/like-compose.yaml 固定测试 MySQL 8.4、Redis 7.4、RabbitMQ 4 系列镜像及摘要 | 不能由测试 Compose 推断生产具体版本、binlog 格式或已有 ES 服务；部署前核验 |
| 文章事务 | ArticleService 分离准备/提交/缓存维护；ArticleCommitService 经代理使用 RC 事务，锁文章、校验权限、同步媒体与 Tag | 在现有事务中增加版本与 Outbox，保留事务边界和锁顺序 |
| 文章模型 | V3 article.hidden；没有草稿/待发布/恢复；published_at 创建后不变 | visible = !hidden，不能凭示例另加 published 状态 |
| 版本 | ArticleEntity/ArticleSnapshot 无 metaVersion；Attempt 仅是本次调用的提交确认 | 持久化 meta_version；不要把 Attempt 变成版本或操作任务 |
| 创建路径 | insert 后仍调用 replace，顺带处理封面、正文、Tag | 创建最终版本应为 1，不能给共用 replace 无条件加一导致初始版本为 2 |
| Tag | article_tag 复合主键；TagService 只有创建和查询，不支持改名/删除 | ES 用 Tag ID 字符串，不需设计 Tag 改名广播 |
| 当前列表 | ArticleQueryService.page / ArticleListHydrator.authorPage 只处理本人的公开文章；SQL OFFSET + total | 保留管理列表契约；新建公开游标响应，抽取公共卡片装配核心，不能直接拿 authorPage 处理跨作者候选 |
| 批量回填 | ArticleListCacheStore 已 MGET；ArticleReader.lists 批量 article、作者展示与 Tag，短只读 RR；正文不在列表 SQL 内 | 复用；新增索引快照读取只选索引必需字段，不为了 ES 查询用户昵称、媒体 URL 或正文 |
| 缓存竞态 | ArticleCache 五秒 token 锁；Lua 校验 token、共享永久 hidden 墓碑、双投影失效；正缓存 270～300 秒，负缓存 20 秒 | 不另造版本栅栏；补充可靠失效入口与公开批量可见性检查 |
| 缓存失效 | ArticleCacheMaintenance 提交后最多三次尝试，失败只日志；宕机无持久补偿 | 当前 void/吞异常入口不能用于决定 MQ ACK；提取可返回失败/抛异常的一次失效方法 |
| MQ 发布 | LikePublisher 已 persistent + CorrelationData future + getReturned；公共配置已 correlated confirm、returns | 复用实现模式和连接工厂，不直接复用点赞错误码、版本分配器、路由或工作队列 |
| MQ 消费 | LikeConsumer 已手动 ACK、有限重试、可靠死信；LikeDeadLetterReplay 已确认重发后 ACK 原消息 | 新索引消费者沿用这些约定；不需要延迟插件或多级重试队列 |
| 浏览统计 | V4 article.view_count + article_view_stat(article_id, stat_date, pv)；日累计差值与总量同事务刷盘 | 七日浏览量有来源；新增分段统计 SELECT 即可，无需再建浏览统计表 |
| Redis 统计 | article-views.lua 的日桶是累计数，保留约两天；flush 每分钟只处理今天和昨天；total 是实时总量 | 不能把桶当未落库 delta 加到 SQL；长期停机超过保留窗口的损失属于既有统计恢复边界 |
| 点赞统计 | V5 当前状态/版本 + 精确 like_count，没有点赞事件历史和七日桶 | 用当前累计点赞量并明确命名；updated_at 不能还原 like7d |
| 排名及前端 | 当前仓库没有全局 Redis ZSET 排名实现，也没有前端源码 | 不能把交接文档中的“切换旧 Redis 排名”当成现有迁移任务；本仓库交付后端 API 与联调契约 |

代码定位：ArticleCommitService.java:29、ArticleRepository.java、ArticleReader.java:22、ArticleListHydrator.java:28、ArticleCache.java:17、ArticleCacheMaintenance.java:14、LikePublisher.java:40、LikeRabbitConfiguration.java:42、ArticleViewSqlService.java:19，均位于 backend/src/main/java/com/febrie/backend 下对应 article 能力包。

## 3. 必须落实的正确性问题

### 3.1 GET 预检查成立，但不能作为写入保护或全链路 ACK 条件

例：A 读 SQL v6，B 读 v7 并先写 ES，A 最后写入。如果只在写前 GET 一次，v6 会覆盖 v7。必须由 Update API 脚本在 ES 当前文档上执行 incoming.metaVersion > stored.metaVersion，冲突后重新执行脚本；最终失败不能 ACK。已有文档的文章元数据更新不得重置 scoreVersion/rankScore。

前置 GET 是成本选择。设重复/过期比例为 p，GET 成本为 G，SQL 快照与更新成本为 D+U，则它大致在 p > G/(D+U) 时才节省资源。正常变更流通常多一次 ES 往返；先默认关闭，采集预检查命中率、SQL 回源量、端到端耗时后再开启。该估算不替代压测。

开启时，只有明确存在且 metaVersion 合法并且 >= 事件版本才能跳过 ES 分支；404 走 SQL，读取失败必须重试。索引元数据快照来自主库的短 RR 事务，文章行、Tag 关系与版本使用同一 read view。另建轻量快照方法，避免 ArticleRepository.find 的 SELECT * 读取大正文。当前主数据源是单数据源；未来分库路由时仍需明确固定主库。

跳过 ES 分支不能跳过缓存失效。相同事件可能是“ES 已成功、Redis 失败”后的重投。SQL 行不存在或版本小于事件版本也不能假装成功：当前只软删除，该情况应隔离告警并对账。

### 3.2 元数据版本与 Outbox 必须随同一次业务提交

新增最终版本 1；每次成功保存递增一次、第一次隐藏递增一次；重复隐藏保持已有版本，不必制造新业务变更。所有成功保存都发同一种通知，即便只改正文/封面，也用于双缓存失效，省去脆弱的字段差异判断。

继续使用现有文章行锁，更新 SQL 原子 +1，事务内读取准确的新版本，再插入 Outbox；Tag、媒体引用任一失败时全部回滚。不要因为 create 也调用 replace 而重复增版。浏览量刷盘与点赞更新不递增 meta_version，避免统计流驱动元数据消息。

### 3.3 六字段 Outbox 可可靠工作，故障闭环还依赖消费与恢复协议

领取短事务、数据库时间、逐次新 token、事务外发布、成功/失败操作都匹配 id + token，可处理多 Relay、租约过期、确认丢失及旧回调。没有 retry_count 不影响正确性，固定退避加抖动足够。

必须补齐以下实现细节：

1. available_at 的等待租约必须覆盖整条发送的排队、连接、send 和 Confirm 时间；单个 Confirm 超时小于租约还不够。领取不超过当前空闲发送槽位，避免先领 100 条再串行等每条两秒。
2. 每次发送使用新的 CorrelationData；消息 eventId 固定，重发不能复用已经完成的 future。Confirm ACK 且 returned == null 才删除。确认/删除数据库失败均允许租约后重复。
3. 本次专用 direct exchange 的工作 routing key 只绑定索引工作队列，不配其它兜底接收队列或 alternate exchange 掩盖 NO_ROUTE。启动/部署核验拓扑。
4. Outbox 删除只表示可靠交给 Broker。工作队列到 DLQ 的转发也必须可靠，DLQ 要有保留、告警与重放入口；普通默认 DLX 不能自动获得至少一次安全性。
5. 自动租约恢复不等于 DLQ 自动完成业务。明确自动重试耗尽后进入死信，修复后有界重放；低频元数据对账补最新状态。

相关语义与 [Spring AMQP Confirm/Return](https://docs.spring.io/spring-amqp/reference/amqp/template.html)、[RabbitMQ 确认](https://www.rabbitmq.com/docs/confirms)和[死信安全性](https://www.rabbitmq.com/docs/dlx)一致。

### 3.4 两组字段要独立，同时 scoreVersion 必须对应统计快照顺序

元数据只能修改 title、tags、publishedAt、visible、metaVersion；首次创建还初始化 articleId 与统计默认值。分数只能修改 rankScore、viewCountTotal、scoreVersion。禁止无条件 index、整对象 doc 覆盖，以及 score upsert。两个业务版本不能用 ES 的 _version 替代。

只声明“定时批次递增版本”不够：先读旧快照，任务延迟，再领取更大版本，会把旧结果包装成新结果。排名阶段使用第 4 节的一行计数器，将领取版本和读取本批 SQL 统计放在同一短 RR 事务内；counter 行锁持续到快照数据复制完成，ES Bulk 在提交后进行。事务在领号前不得已有旧 read view。每批建议 100～200 篇，不开覆盖全站扫描的长事务。

失败重试保留同一版本与同一已计算 payload；重新读数据必须重新领取版本。分数允许下降，不能用 max(rankScore) 防回退。不同实例/旧请求晚到由分数字段脚本裁决。

### 3.5 “展示前再过滤”必须有可用的权威状态

ArticleListView 没有 hidden；正缓存命中不会读 article.hidden。当前 authorPage 的输入已经由主库选出，且有作者约束，不能推广为 ES 候选入口后仍假设输入可靠。

反例：卡片已缓存 → SQL 隐藏成功 → 提交后进程宕机未写 Redis 墓碑 → 旧 PIT 返回 ID → 卡片缓存命中。再检查一次同样缺失的墓碑仍会展示该文章。文档已声明最终一致，所以该反例不推翻其总体边界；但“任何旧 PIT 中隐藏项都会被展示层排除”的验收口径过强。

本次公开查询在每批卡片返回前执行主库 SELECT id ... WHERE hidden = FALSE，按确认的可见 ID 过滤。保证在该 SQL 状态读取前已经提交的隐藏不返回；状态检查与隐藏并发重叠的请求允许按读到的状态完成。仅卡片缺失才查全文卡片数据，不能再宣传“缓存全命中就完全不访问 MySQL”。这是一次 PK IN 轻量校验，不是逐条回源或新中间件。

现有详情接口仍有其原来的最终一致边界；若产品要把同一隐藏承诺扩展到详情，应复用同一个权威状态检查，并单独修改详情契约/测试。不能仅因为新的列表安全过滤就宣称所有访问路径立即禁止访问。

缓存回填仍复用锁、Lua、TTL 与墓碑。锁过期后的旧 token 不得写缓存，但返回本地已读卡片仍可能陈旧；最新可见性由上述主库检查负责。用户/Tag 展示名称等缓存陈旧不属于 ES 版本保护范围。

### 3.6 游标只能经过确定处理的候选

成功组装或已由权威状态确认隐藏/不存在，才可消费该 hit。SQL/Redis 超时、反序列化失败、ES 超时或分片失败不是“文章不存在”，不能吞异常推进游标。失败页不发布新游标，客户端重试原游标。

取 30 条、处理到第 23 条凑齐 20 篇时，游标取第 23 条完整 sort；24～30 保留给下页。达到候选/轮次预算但尚未凑齐时可返回短页甚至空页，并携带可继续的 nextCursor；前端不能仅凭 items.length == 0 认定结束。明确耗尽才 nextCursor = null。

### 3.7 重建不能依赖已经删除的 Outbox

初次上线先让新增索引接收增量，再按主键分段回填，全部走同一版本条件脚本。默认不做零停机重建：维护窗口暂停文章写入，排空/核对，再回填与切换。未来如需在线重建，要先让所有消费者对新旧索引双写且确认生效，再开始回填，任一目标失败不能 ACK；最后原子切换别名。两个消费者竞争同一个工作队列不构成双写。

## 4. 最终 SQL 设计

以下 SQL 是实施规格，未执行。新增 Flyway 版本，禁止改动已应用的 V1～V5；排名计数器可在排名阶段单独迁移。

```sql
-- 业务保存/隐藏版本；统计写入不修改它，老数据和新文章从 1 开始。
ALTER TABLE article
    ADD COLUMN meta_version BIGINT NOT NULL DEFAULT 1 COMMENT '文章检索与缓存通知版本',
    ADD CONSTRAINT ck_article_meta_version CHECK (meta_version >= 1);

-- 不可变事件；available_at 同时表示重试时刻和当前领取租约到期时刻。
CREATE TABLE article_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    meta_version BIGINT NOT NULL,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_token BINARY(16) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_article_outbox_available (available_at, id),
    CONSTRAINT ck_article_outbox_version CHECK (meta_version >= 1)
) ENGINE=InnoDB COMMENT='文章变更可靠交接，确认投递后删除';

-- 只在推荐分阶段增加：单行持久序号，不记录任务状态、历史或失败项。
CREATE TABLE article_score_clock (
    id TINYINT NOT NULL PRIMARY KEY,
    score_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_article_score_clock_id CHECK (id = 1),
    CONSTRAINT ck_article_score_clock_version CHECK (score_version >= 0)
) ENGINE=InnoDB COMMENT='排名统计快照批次序号';
INSERT INTO article_score_clock (id, score_version) VALUES (1, 0);
```

Outbox 不设 article_id 唯一键，不加 payload、状态和外键级联删除。文章没有物理删除业务；外键不能代替该业务约束。created_at 保留用于积压年龄；初期不为监控另加索引，监控低频执行。统计日表现有 (article_id, stat_date) 主键适用于一批文章的七日范围读取，不先加日期二级索引。

文章更新示意：

```sql
-- 调用前已有文章行锁，且权限/隐藏校验通过；与内容、Tag、媒体引用在一个事务。
UPDATE article
SET title = :title, meta_version = meta_version + 1
WHERE id = :article_id;
-- 上面只展示相关列；实施时保留现有正文、封面等显式更新逻辑。
SELECT meta_version FROM article WHERE id = :article_id;
INSERT INTO article_outbox (article_id, meta_version)
VALUES (:article_id, :new_meta_version);
```

Relay 协议：

```sql
-- 通过独立事务代理执行短 RC 领取事务；数据库时间统一租约。
START TRANSACTION;
SELECT id, article_id, meta_version
FROM article_outbox
WHERE available_at <= CURRENT_TIMESTAMP(3)
ORDER BY available_at, id
LIMIT :free_slots
FOR UPDATE SKIP LOCKED;
-- ids 为刚刚选中的有界集合；为空时不执行 IN 更新。
UPDATE article_outbox
SET available_at = TIMESTAMPADD(SECOND, :lease_seconds, CURRENT_TIMESTAMP(3)),
    claim_token = :new_binary_token
WHERE id IN (:ids);
COMMIT;

-- 发布在事务外。仅同次 token 的持有者可以结束本次领取。
DELETE FROM article_outbox WHERE id = :id AND claim_token = :token;

-- 失败、超时、结果未知都可重试；jitter_ms 为有界正数。
UPDATE article_outbox
SET available_at = TIMESTAMPADD(MICROSECOND, :jitter_ms * 1000, CURRENT_TIMESTAMP(3)),
    claim_token = NULL
WHERE id = :id AND claim_token = :token;
```

成功删除不要求租约尚未到期：只要 token 仍相同，就没有新领取者；已经发生重领则 token 不同，旧回调无法删除。失败落库本身失败时也保留租约自动恢复。初期配置：轮询约一秒、租约三十秒、Confirm 等待两秒、固定五到十五秒退避；最大领取量不超过空闲发送槽位，连接/send 也有超时与并发上限。配置与测试必须验证整次发布预算小于租约，不只比较 Confirm。

[MySQL 官方说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)支持 SKIP LOCKED 用于此类队列表；部署使用 row-based binlog，领取事务不承担一般一致查询。

分数版本与读取：在一个新的短 RR 事务内先 UPDATE article_score_clock SET score_version = score_version + 1 WHERE id = 1，再读该版本和本批文章/浏览日桶/点赞计数；第一条一致性 SELECT 必须在取得该锁之后。返回不可变快照并 COMMIT，随后计算并 Bulk。该 counter 在一批 SQL 读取期间串行化版本与快照，网络请求不持有锁；不需要另加租约、调度锁或任务历史。多实例重复扫描可以正确收敛，但需限制总扫描速率；首版可配置只在一个实例启用定时扫描。

公开可见性检查：

```sql
-- 从主库读取；每批一次，卡片命中也执行；没有 ID 时不发 SQL。
SELECT id FROM article WHERE id IN (:ids) AND hidden = FALSE;
```

## 5. 最终 ES 设计

物理索引 article_v1；article_read 指向一个在线索引；article_write 指向相同索引并标记 is_write_index。不设置自定义 routing，不关闭 _source；请求经别名并要求写别名存在，关闭自动创建索引，防止别名拼错后误建。开发环境分片/副本按实际单节点资源配置，不能照搬到生产。

```json
{
  "mappings": {
    "dynamic": "strict",
    "properties": {
      "articleId": {"type": "long"},
      "title": {"type": "text", "analyzer": "cjk"},
      "tags": {"type": "keyword"},
      "publishedAt": {"type": "date"},
      "visible": {"type": "boolean"},
      "metaVersion": {"type": "long"},
      "rankScore": {"type": "double"},
      "viewCountTotal": {"type": "long"},
      "scoreVersion": {"type": "long"}
    }
  }
}
```

_id 为十进制 articleId。Tag 是 ID 字符串；保留 articleId 的 doc_values 用于数值排序，不能直接按 _id 排序。删除 summary，因为无检索用途；暂不存 authorId，因为本期没有作者公开筛选，已有本人管理列表仍走 SQL。正文、封面、昵称、计数展示和个人状态均不进入索引。title 使用内置 cjk，真实标题验证后再决定是否需要额外分词插件。

元数据 scripted_upsert 的参数化脚本语义如下，upsert 传空对象、scripted_upsert=true；脚本在创建分支完整建档，在更新分支只改元数据：

```text
// 参数由服务端快照构造；进入此方法前已验证文章 ID、版本和字段完整性。
if (ctx.op == 'create') {
    ctx._source.articleId = params.articleId;
    ctx._source.metaVersion = 0L;
    ctx._source.rankScore = 0.0;
    ctx._source.viewCountTotal = 0L;
    ctx._source.scoreVersion = 0L;
}
if (params.metaVersion > ctx._source.metaVersion) {
    ctx._source.title = params.title;
    ctx._source.tags = params.tags;
    ctx._source.publishedAt = params.publishedAt;
    ctx._source.visible = params.visible;
    ctx._source.metaVersion = params.metaVersion;
} else {
    ctx.op = 'noop';
}
```

分数更新不带 upsert：

```text
// 每个参数批次来自同一 SQL 统计快照；重试沿用完全相同的参数。
if (params.scoreVersion > ctx._source.scoreVersion) {
    ctx._source.rankScore = params.rankScore;
    ctx._source.viewCountTotal = params.viewCountTotal;
    ctx._source.scoreVersion = params.scoreVersion;
} else {
    ctx.op = 'noop';
}
```

设置有界 retry_on_conflict，例如 3；最终 409、429、5xx 分类退避，Bulk 必须逐项检查。缺元数据的 score 更新返回 404，触发元数据检查/补齐后由下一轮重算，不创建半文档。映射/脚本类 400 隔离告警，不无限重试。正常索引持久化成功不等于搜索已刷新；默认刷新即可，不对每条变更 refresh=true。这些接口行为需用选定客户端/服务器跑真实集成测试，依据 [Elasticsearch Update API](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/update-document)。

首版统计口径：按既有 Asia/Shanghai 业务日取今天及前六天（含今天未完结自然日），不是精确滚动 168 小时。view7d = SQL 日桶之和；likeTotal = SQL 当前点赞计数；viewCountTotal = article.view_count。示例 rankScore = ln(1+view7d) + 3*ln(1+likeTotal) + lambda*publishedAtEpochHours，权重可配置、结果必须有限。它是统一线性时间衰减的等价相对排序，不宣称为七日点赞热度或指数衰减。

每五分钟 keyset 分段全量重算，数据库限速、Bulk 限并发，失败项有限原样重试，下一轮完整扫描构成持久补偿来源；不另建失败明细表。全量扫描自然覆盖窗口滑出和无新增事件的老文章。将来数据量大到无法在目标周期完成时，再设计活跃候选和日边界候选集。Redis 只供卡片实时展示，SQL 统计本来就约一分钟刷盘，额外低延迟不值得首版跨存储快照的复杂度。

## 6. 最终 RabbitMQ 与消费设计

命名按环境隔离，示意 <prefix>.article.index.*，与点赞命名空间和工作队列分离。

| 资源 | 规格 |
|---|---|
| change.x | durable direct exchange；工作 routing key 为 article.index.changed |
| work.q | durable quorum queue，仅绑定上述工作路由；无过期/自动删除/消息 TTL |
| dead.x | durable direct exchange；路由 article.index.dead |
| dead.q | durable quorum queue，死信停放；无消息 TTL，重放保留原 eventId |
| 工作队列策略 | dead-letter-strategy=at-least-once、overflow=reject-publish、有限容量、delivery-limit=20；按部署版本核验策略实际生效 |
| 死信队列策略 | overflow=reject-publish、有限容量告警、delivery-limit=-1；不得满时丢旧消息 |
| 发布 | persistent 消息、mandatory=true、correlated confirms、returns=true；每次发送独立 CorrelationData |
| 消费 | MANUAL ACK；初始并发 2、prefetch 10，可配置；故障重试有界 |

消息协议只包含 eventId、articleId、metaVersion；Java 用 long，若在 JS 工具中处理则作为十进制字符串避免精度损失。消息不携带旧文章内容或按事件类型重放旧状态，统一回源当前状态。eventId 也用作 messageId，但 Broker 不负责按 messageId 去重。

消费者顺序：解码和校验 → ES 元数据更新/确定版本 noop → token 锁内双缓存失效成功 → ACK。已取得最新隐藏快照时可以复用 HIDE，否则统一 INVALIDATE 双投影且永不清除既有墓碑，不为缓存失效再读取 SQL。

现有 afterCommit 方法仍保持“失败不覆盖业务成功”的接口；另提取一次可靠维护入口，锁忙、Redis 异常、token 失效都明确失败，供消费者重试。若 ES GET 判断无需更新，也必须继续缓存步骤。ES 成功后 Redis 失败会导致重投，两个步骤都幂等；首版接受 Redis 故障也延迟该队列处理的耦合，故障隔离需求被实际数据证明后再拆队列。

临时错误最多五次，事务外按 1/2/4/8 秒退避；耗尽或确定的非法消息 basicNack(requeue=false) 进入可靠 DLQ；进程退出/通道断开保留未确认消息。系统性 ES 故障可暂停监听并告警，避免整个队列快速搬入 DLQ。恢复后有界重放：新发布 Confirm+Return 成功才 ACK 原死信；未知结果保留原死信。不引入延迟插件、TTL 重试环或消费者去重数据库表。

## 7. 卡片装配与公开游标 API

保留现有 /api/users/me/articles 页码管理查询。新公开 API 可定义为 /api/articles/search 和 /api/articles/feed，响应使用 items、nextCursor；不返回可能与卡片过滤不一致的精确 total，不修改原 ArticlePage 语义。

按层分工：搜索服务取得 ES 候选及 sort；公共卡片装配核心复用 ArticleListCacheStore、ArticleReader.lists 和 ArticleCache；作者管理包装层继续校验作者归属；公开包装层进行主库批量可见性校验；最终只对实际返回 ID 批量读取 ArticleViewService.totals 与 LikeQueryService.summaries，匿名 likedByMe=false，个人状态不进共享缓存。

列表顺序永远按 ES hit 顺序重排，不能依赖 SQL IN 顺序、Map 遍历顺序或 MGET 缺失项压缩后的顺序。回填必须先取得锁再开始短 RR 快照；每批只回源仍缺失的 ID，依赖故障不能写负缓存。同一请求不能持有 A 锁阻塞等待 B 锁。

现有五秒 token 锁已经防止失去锁的旧读者污染共享缓存；先不引入另一套缓存版本栅栏。现有 WAIT_BUDGET=300ms 只是轮询预算，Redis 超时两秒、连接池等待三秒可能超过它，新增 API 必须明确整页预算与各 I/O 超时。增加本机有界 SQL 回源名额，满时快速失败；不能把连接池当无界排队策略。BatchReadGate 可借鉴其在途合并，但当前绑定 LikeErrors，不直接让文章内容依赖点赞业务错误；只有确需复用时做小范围通用化。

排序白名单：

| 场景 | 排序 |
|---|---|
| 标题查询 | _score DESC, publishedAt DESC, articleId DESC |
| Tag 最新 | publishedAt DESC, articleId DESC |
| Tag 热门 | viewCountTotal DESC, publishedAt DESC, articleId DESC |
| 全局推荐 | rankScore DESC, publishedAt DESC, articleId DESC |

所有查询过滤 visible=true；Tag 使用 ID term/terms，多个 Tag 的 AND/OR 语义必须固定并进入查询指纹；不接受客户端 ES DSL。初期 pageSize<=50、Tag 数<=5、关键词<=100 个 Unicode 码点、最多三轮候选、候选总量<=150；这些是容量起点，以压测调整。

公开分页统一 PIT + search_after，按最后已处理候选推进。cursor 包含协议版本、查询指纹、完整 sort JSON 类型和值、最新 PIT ID、过期时间，用 HMAC 签名且多实例共享密钥，不额外建 Redis 游标会话表。不要将 long/double sort 值经浏览器数值重编码。keep_alive 可从两分钟开始，并设会话总寿命与新建 PIT 限流；耗尽/明确结束尽量关闭，放弃会话依赖过期释放。

检索超时/分片失败不能视为正常结束；PIT 过期返回可识别错误让用户刷新，不静默换 PIT 拼接旧游标。排序值冻结而卡片实时浏览/点赞变化是预期行为。旧 PIT 保留旧候选，但隐藏由 SQL 状态检查过滤；新发布文章要刷新后才进入本次会话。[Elasticsearch 官方分页说明](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results)要求保留完整 sort（包括 PIT 的 _shard_doc）并使用最新 PIT ID。

## 8. 分阶段实施与提交

默认继续在本地 master，保留工作区用户已有未跟踪文档。每阶段可单独审阅、回滚与提交，提交信息使用规定中文格式；本轮没有替用户创建提交。

| 阶段 | 交付与边界 | 建议提交 |
|---|---|---|
| 1 | ES 客户端/独立测试环境、Mapping/别名、元数据读取与两组参数化脚本；不切现有查询 | feat(article-search): 增加索引契约与版本保护写入 |
| 2 | V6 版本与六字段 Outbox；create/update/hide 同事务；现有 DTO/Mapper 同步字段 | feat(article-index): 记录文章版本与事务事件 |
| 3 | Relay 短事务、令牌租约、Confirm/Return、专用 Quorum work/DLQ、消费者与可靠缓存失效、积压指标 | feat(article-index): 实现可靠投递与索引消费 |
| 4 | 可重复初次导入、分段对账、DLQ status/replay、维护窗口重建；验收恢复链路后再暴露搜索 | feat(article-index): 增加初始化与故障恢复 |
| 5 | 抽取公共卡片核心、权威可见性检查、标题/Tag 最新查询及 PIT 游标；本人管理列表不改契约 | feat(article-search): 增加公开检索与游标分页 |
| 6 | 单行 score clock 迁移、SQL 快照、分段全量排名、Bulk 逐项处理、Tag 热门与全局推荐 API | feat(article-feed): 增加统计排名与全局信息流 |

阶段 3 内如差异过大，再拆 Relay 与消费者两次提交，不在一个提交里同时重写点赞/缓存框架。新包按能力放 article.search/article.index（或遵循现有能力划分的小包），同步显式 MapperScan、XML namespace、测试和离线维护注册。新代码写必要中文注释，标明调用前提，不在每层机械重复参数校验。

SQL 事务不访问 MQ/ES/Redis；内容转换仍在事务外。初次上线要先启用写路径版本/Outbox，再确保目标索引接收增量并回填，查询入口保持关闭直到对账完成。服务重启、回滚时不要让旧写实例在新版本启用后继续不带 Outbox 地修改文章；升级需要先停止旧写实例再统一启动兼容版本。

## 9. 测试、压测和验收

真实 MySQL/Redis/RabbitMQ/ES 使用专用测试库、命名空间与 vhost，复用既有 infra/test 的隔离方式。并发测试用屏障和受控故障，不靠随机 sleep。单元测试只覆盖契约/分支，可靠性验收必须有真实服务。

| 范围 | 必测情况与断言 |
|---|---|
| 业务事务 | 新文章最终版本 1 且一条 Outbox；更新/Tag/隐藏增版；重复隐藏不增版；媒体/Tag/Outbox 失败全部回滚；统计刷盘不增元数据版本 |
| 提交崩溃 | 子进程 SQL commit 后、缓存维护前强制退出，重启由 Outbox 收敛 ES 与双缓存 |
| 多 Relay | 两实例不重复持有同一未过期租约；A 过期后 B 重领，A 迟到成功/失败回调都不能修改 B 的行；数据库删除失败仍可重发 |
| 发布与恢复 | ACK+Return 不删事件；确认丢失/断连/不存在交换机/队列满/未绑定都保留事件；MQ 恢复后收敛；领取批次不在本地排队到过期 |
| 消费/DLQ | ES 成功而 ACK 丢失；ES 成功但 Redis 失败重投仍失效缓存；DLQ 目标不可用源消息保留；重放发布未知时不 ACK 原死信 |
| 元数据脚本 | v8/v11 重复乱序；旧 SQL 快照最后写不得倒退；主库 RR 的文章/Tag 一致；隐藏先到、旧新增后到不会复活；缺 SQL 行进入异常流程 |
| 版本预检查 | 开启时 ES v10 对 v8/v10 不查 SQL但仍执行缓存步骤；关闭时直接快照+条件写同样正确；GET 失败不 ACK |
| 双版本并发 | 创建完整默认分数字段；meta/score 交错不覆盖；score 无文档只 404；冲突耗尽重试；分数下降可生效；同版本重试 noop |
| 统计批次 | 两实例版本与 SQL read view 顺序一致；旧 payload 晚到不覆盖；失败重试不换版本；重新采样须新版本；七日滑出、午夜、取消点赞、零值、部分 Bulk 失败 |
| 卡片/隐藏 | 缓存热命中+缺失墓碑+旧 PIT+已提交隐藏仍被 SQL 过滤；跨作者不误触 authorPage；缓存 miss 一批 SQL，不查正文；锁过期旧回填拒绝；依赖失败不记负缓存 |
| 分页 | 第 23 hit 填满页后不跳过 24～30；排序同分、long 精度、最新 PIT ID；隐藏补齐、空页带 nextCursor；错误不能推进；cursor 篡改/跨查询/PIT 过期 |
| 初始化/重建 | 回填与变更交错，低版本不覆盖高版本或分数；隐藏行也导入墓碑；不从已删 Outbox 重放历史；别名切换后读写目标一致 |
| 容量 | 冷缓存、热点锁、MQ 积压、ES 429/停机；统计 SQL 条数与并发，不出现逐篇 SELECT；记录 p95/p99、池等待、PIT 数和最老事件年龄 |

GET 开/关分别测正常流与高重复流，依据耗时和 SQL 节省选择默认值；只要脚本相同，不把性能开关变成一致性开关。查询侧给定 pageSize/候选预算后，SQL 次数应随批次增长而非随文章数增长；锁命令当前仍可能按 miss 数增长，达到瓶颈再考虑 pipeline，不先重写缓存协议。

上线最低监控：Outbox 总量与最老年龄、确认失败/Return/超时、租约重领、工作队列和 DLQ 积压、索引更新失败和延迟、最近成功排名扫描时间、Bulk 分项失败、卡片 miss/回源并发、隐藏过滤量、PIT 使用量。复用 Micrometer/Actuator，不新增监控中间件。

本次已执行：在 backend 目录运行 `mvn -q -Dtest=ArticleListHydratorTests,LikePublisherTests,ArticleViewFlushTests,ArticleViewSqlServiceTests test`，共 11 项通过（4+3+1+3），零失败/错误/跳过。这些是现有实现的定向回归，不能证明尚未编写的 ES、Outbox 或真实 Broker 故障链路；本轮未运行新增 ES 集成或全量故障测试。
