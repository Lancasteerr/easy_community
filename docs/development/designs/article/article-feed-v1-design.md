# 文章增删改查与异步处理 V1 设计方案

> 2026-11-11更新：本文保留历史设计背景；本人SQL分页、旧游标及索引字段约定已由[统一查询v2契约](../../../public/operations/search/zh-CN/article-search-operations.md)替代。当前公开作者搜索与本人独立接口共用ES候选链路，均仅展示公开文章。

> 状态：修订审核稿，尚未实施。更新日期：2026-10-31。
>
> 本次按最新要求收敛范围：只实现文章增删改查、必要的 Tag 元数据与媒体绑定，以及 SQL 命令 → 缓存事件的异步链路。信息流、Tag 排序、浏览量和点赞均不进入本次实施计划。文件路径沿用上一版，正文以本版为准。
>
> 依据：[文章异步设计讨论](https://chatgpt.com/share/6ae4c215-c400-83e8-aa04-3cdc6a8f30e0)、[现有文件服务](../../../public/guides/media/zh-CN/media-service.md)及[后端架构约定](../../../public/architecture/backend/zh-CN/backend-architecture.md)。本文将“已确定方向”“实施建议”“仍需接受的限制”分开说明，重点列出异步引入后的竞态及验收方法。

## 1. 本次范围与核心决定

### 1.1 实现范围

| 包含 | 本次不实现，也不预建表、Key 或空实现 |
|---|---|
| 异步创建、修改、软删除文章 | 首页信息流、推荐算法、版本榜单和快照游标 |
| 正式详情、编辑用 JSON、作者文章管理列表 | Tag 热门／最新文章排序及 Tag 文章流 |
| 正文 JSON 校验、安全 HTML 生成 | 浏览计数、刷盘、七日指标、点赞关系及统计 |
| Tag ID 关联及最小目录支撑 | 个人推荐、曝光记录、随机探索 |
| 封面与正文图片归属校验、引用集合维护 | ES 同步实现；仅保留事件扩展点 |
| RabbitMQ 两阶段接力、重投、失败转存与恢复 | Outbox、任务表、完整修订历史、额外文章 version |
| 详情缓存、负缓存、删除标记、回源与失效协调 | 为列表单独搭建卡片缓存或通用消息编排平台 |

Tag 只作为文章元数据：允许提交已存在的 tagId，管理员新增 Tag、读取目录提供最小录入支撑，不扩展 Tag 管理及排序系统。

作者管理列表是普通 SQL 管理查询，不是推荐流，不提供“全站连续浏览”或冻结分页视图的承诺。读取文章不记录浏览，DTO 不伪造点赞量／浏览量为零。

### 1.2 保留的设计方向

1. MySQL 只保存完整提交的正式文章。新建在提交前没有正式记录；编辑期间旧正文保持可读，成功时一次替换。
2. 正式文章只有公开与隐藏：hidden=false／true；隐藏是终态，普通更新不能恢复。
3. JSON → HTML 在事务外执行；文章、HTML、JSON、Tag、媒体引用在同一个显式 READ_COMMITTED 事务提交。
4. API 向 RabbitMQ 投递命令，收到 Confirm 且路由成立才返回 202。
5. SQL Worker 在提交后发送 ARTICLE_CHANGED，确认下一阶段消息后才 ACK 原命令。
6. 更新复用严格递增的 updated_at 作为并发令牌，不增加 article.version。
7. 同一命令重投不重复改写已提交内容，但允许重复补发缓存事件。
8. 详情回填与缓存失效共用同一把文章缓存锁，并原子检查 token，不能仅依赖短 TTL 消除竞态。
9. 不引入任务表，因此不承诺可靠的逐命令完成查询或自动反馈所有异步错误。

### 1.3 三种结果不能混用

| 阶段 | 含义 | 不代表什么 |
|---|---|---|
| 已受理 | Broker 已确认接收命令，HTTP 返回 202 | 不代表 SQL 已提交 |
| SQL 已完成 | 正文与关联已原子提交 | 不代表 Redis 已失效 |
| 缓存已收敛 | 对应缓存事件成功处理 | 不撤回已发出的旧 HTTP 响应 |

202 不显示为“已发布成功”或“已彻底删除”。删除仍是最终一致：从受理到 SQL、再到删除标记生效之间，其他读者可能继续读取旧内容。

## 2. 组件与一致性边界

~~~mermaid
flowchart TD
    API["Article API：鉴权和基础预检查"] -->|"持久消息；Confirm 与路由检查"| CQ["article.command.q"]
    CQ --> SW["SQL Worker：创建／修改／隐藏"]
    SW --> PRE["新增／修改：事务外生成安全 HTML"]
    PRE --> DB[("MySQL：文章、Tag、媒体引用原子提交")]
    SW -->|"隐藏：短事务"| DB
    DB --> EVENT["发布 ARTICLE_CHANGED，仅携带 articleId"]
    EVENT --> CACHEQ["article.cache.q"]
    EVENT --> CONF{"事件 Confirm 且路由成立？"}
    CONF -- 是 --> ACK["ACK 原命令"]
    CONF -- 否 --> RETRY["保留原命令，退避重试／可靠失败转存"]
    CACHEQ --> CW["Cache Worker：与回源共用文章缓存锁"]
    CW --> SQL["锁内读取 SQL 当前状态"]
    SQL --> REDIS[("Redis：失效详情／负缓存，维护删除标记")]
    GET["Article Query：详情／编辑／管理列表"] --> READ["详情可走缓存；编辑与管理列表查主库"]
    READ --> DB
    READ --> REDIS
~~~

所有组件可运行在同一个 Spring Boot 应用中。两个 Worker 是职责区分，不要求拆微服务。

必须保持：

- API 的预检查只是尽早失败；是否可以写入，以 Worker 短事务内检查为准。
- SQL 事务内不等待 MQ、Redis、对象存储或渲染器。
- 缓存事件表达“重新维护当前状态”，不携带旧正文来覆盖缓存。
- 不存在、隐藏、更新冲突、依赖异常分别处理；SQL 异常不能写成负缓存。
- 隐藏不解除媒体引用；正文修改才按新集合增删引用。
- 锁过期不意味着旧线程已经停止。SQL 靠行锁和条件保护，Redis 靠 token 条件写保护。
- Single Active Consumer 只减少并发，不充当数据库锁、缓存写入屏障或请求顺序保证。

## 3. 数据模型与时间令牌

### 3.1 本次涉及的表

| 表 | 主要字段／约束 | 用途 |
|---|---|---|
| article | id、author_id、title、summary、content_json、content_html、cover_media_id、hidden、created_at、updated_at、published_at | 正式文章；id 为服务端预分配正 BIGINT |
| tag | id、name 等必要字段 | 受控 Tag 目录 |
| article_tag | 主键 (article_id, tag_id)，反向索引 (tag_id, article_id) | 文章元数据关系去重 |
| media_file | 沿用现有八字段 | 上传者、用途、READY 状态、可信大小及回收 |
| media_reference | 沿用主键 (ref_type, ref_id, file_id) | 同一文章／文件最多一条引用 |

不创建文章统计、点赞、信息流、任务或 Outbox 表。新增迁移不重写已存在的用户及媒体建表历史。

媒体引用统一 ARTICLE_IMAGE；封面与正文合并去重。不新增 ref_count，不把讨论中的 ARTICLE_CONTENT 或 object_key 示例落入当前表结构。

### 3.2 时间语义与防旧写

- created_at、published_at 在首次正式创建提交时由后端生成；普通修改不刷新 published_at。
- updated_at 使用 UTC DATETIME(6)，正文／元数据变更、首次隐藏才更新。
- 每次真实改写取“数据库当前 UTC 时间”与“旧值加一微秒”的较大值，保证该文章严格递增。
- 不使用自动 ON UPDATE 来混入非内容变化，也不让其他写入路径绕过该规则。
- 前端原样传回 expectedUpdatedAt，保留六位小数，不经 JavaScript Date 转换丢失微秒。
- BIGINT ID 用 JSON 字符串传输，媒体 ID 继续使用标准 UUID。

updated_at 是已有字段承担的并发令牌，不是“没有版本字段便无需并发控制”。正文与引用集合必须同时受这一令牌约束。

## 4. HTTP 与消息契约

### 4.1 接口建议

| 接口 | 返回／语义 |
|---|---|
| POST /api/articles | 发送 CREATE；202 返回 articleId、commandId、acceptedAt |
| PUT /api/articles/{id} | 发送 UPDATE，必须有 expectedUpdatedAt；202 |
| DELETE /api/articles/{id} | 发送隐藏命令；202；对已隐藏且授权成立的文章可重复受理 |
| GET /api/articles/{id} | 普通读取正式详情；不存在／已隐藏为 404 |
| GET /api/articles/{id}/edit | 作者查主库，取得一致的 JSON、Tag、封面和 updatedAt；已隐藏返回状态冲突 |
| GET /api/users/me/articles | 作者管理列表，仅查询正式表，可筛公开／隐藏 |
| GET /api/tags | 读取录入用目录 |
| POST /api/admin/tags | 管理员新增 Tag 的最小支撑 |

本次默认作者创建、修改、删除自己的文章；管理员／巡查员代删权限列为待审核项，不因具有角色就自动获得其他作者编辑权。

沿用 Session + CSRF。普通 GET 是否允许游客需明确配置；初期沿用当前 /api/** 登录要求，不因内容叫“公开”就自动放开安全链。

作者管理列表直接查主库，建议按 id 倒序、使用 beforeId 做普通管理分页。异步提交的较小 ID 可能晚于一次翻页才落库，需刷新才能看到；列表不显示排队任务，不引入信息流或快照。

### 4.2 输入、消息和同步错误

内容输入只包含 title、summary、contentJson、schemaVersion、coverMediaId、tagIds，以及修改时的 expectedUpdatedAt。不能信任客户端指定的 authorId、HTML、时间或状态。

命令最少包含 commandId、operation、articleId、服务端确定的 actorId、payload 及所需 expectedUpdatedAt。事件最少包含 eventType=ARTICLE_CHANGED、articleId，commandId 仅作关联日志。commandId 本身不提供持久化去重，也不是任务记录。

API 可同步拒绝格式错误、明显越权、目标不存在、已隐藏或时间戳冲突；消息入队前通过预检查，不保证消费时仍然成立。

发送超时／结果未知返回依赖错误，不返回 202，也不能声称“任务肯定没有执行”。API 线程、Broker 确认与 Worker 执行互相独立，SQL 甚至可能早于客户端收到 202 完成。HTTP 断开或超时不会撤销已经入队的命令，前端不得据此承诺操作取消。

建议限制消息总大小 1 MiB、Tag 最多 5 个，并限制 JSON 深度和节点数；这些为待审核初值，不依赖 Broker 默认上限兜底。

### 4.3 两类幂等边界

- **MQ 重投**：同一 CREATE 始终使用固定 articleId，同一 UPDATE 保留原 expectedUpdatedAt；Worker 可防重复实体和旧写。
- **HTTP 重新提交**：如果后端重新分配 articleId，仍可能产生第二篇文章。没有可靠请求去重存储或可复用的创建凭据，不能承诺端到端幂等。

V1 暂不增加任务表。CREATE 响应丢失时前端不自动无限重发；用户可从作者管理列表核对，但这不是精确的逐请求结果证明。若不接受该限制，应在实施前审核最小的 HTTP 创建去重方案。

## 5. 写入前提、鉴权与锁顺序

### 5.1 事务准备和锁内校验分开

事务外负责解析受限 JSON、提取媒体 ID、生成安全 HTML、做可选快速检查，不持有 SQL 行锁。

真正写入时使用显式 READ_COMMITTED：

1. 经 user.application 提供的不含密码的事务读取入口，检查／锁定操作人可用状态。
2. 创建时插入文章取得新行锁；更新／删除时锁定现有文章行。
3. 在锁内检查作者、hidden、expectedUpdatedAt，必要时验证 Tag 存在。
4. 调用 MediaReferenceService，按文件 ID 排序锁新旧媒体并复核所属权、READY、用途、可信大小。
5. 一次提交正文、HTML、Tag、引用和 updated_at。任何异常回滚整个事务。
6. SQL 提交后，事务外发布缓存事件。

建议操作人行使用共享锁，将“账号注销”与“新的文章变更”明确排序，统一顺序为“操作人 → 文章 → 按 ID 排序的媒体”。该账户读取契约需要在用户应用层最小补充，不能直接调用会加载密码哈希的现有 lockById。若 Tag 后续支持删除，再评估其锁顺序；本次 Tag 仅新增，不引入额外状态机。

如果注销先取得互斥锁并提交，排队文章命令不能再产生新写入；文章事务先获得共享锁则允许其完成，注销等待。已有文章不会因注销自动批量隐藏，属于独立产品策略。

命令不是浏览器请求，不在 Worker 重放 Cookie／Session。建议按执行时账号可用性和业务权限处理，不把“用户退出登录或改密”自动解释为撤销已受理命令；若需要可撤销任务，应另设计契约。

### 5.2 提交重试先判断是否仍需写

已经提交或明显过时的命令先走“读取正式状态、停止旧写入、补发事件”路径，不重新绑定旧媒体，不重新校验旧正文中的全部文件。

这是为了避免：

1. 文章已成功提交，事件发送前退出。
2. 后续文章被编辑／隐藏，原 payload 的部分图片不再属于当前正文。
3. 重投时先校验旧图片失败，导致本该补发的失效事件永远发不出。

只读补发不代表这次内容修改成功；它只修复当前 articleId 的缓存。目标身份不符、来源不可信等永久错误仍应拒绝。

## 6. 创建、修改、删除流程

### 6.1 创建

~~~mermaid
flowchart TD
    A["API：鉴权、参数预检查、后端固定 articleId"] --> B["发布 CREATE"]
    B --> C{"Confirm 且路由成立？"}
    C -- "否／未知" --> U["返回结果不明确的依赖错误，不报告已受理"]
    C -- 是 --> ACCEPT["返回 202"]
    B --> W["SQL Worker 独立消费"]
    W --> EXIST{"该 ID 已存在合法正式文章？"}
    EXIST -- 是 --> REPAIR["不覆盖正文／引用，进入事件补发"]
    EXIST -- 否 --> PRE["事务外生成 HTML、提取媒体"]
    PRE --> TX["RC 事务：账户校验、插入文章、Tag 与媒体最终校验、绑定引用"]
    TX --> COMMIT{"事务结果？"}
    COMMIT -- "成功" --> EVENT["发布 ARTICLE_CHANGED"]
    COMMIT -- "失败／未知／并发主键冲突" --> R["重试①：回滚后重新读取；永久错误或超限可靠转失败队列"]
    R --> W
    REPAIR --> EVENT
    EVENT --> CONF{"下一阶段 Confirm 且路由成立？"}
    CONF -- 否 --> R
    CONF -- 是 --> ACK["ACK 原 CREATE"]
    EVENT --> CACHE["Cache Worker：共享缓存锁内重读 SQL，清详情与负缓存"]
    CACHE --> OK{"Redis 条件操作全部成功？"}
    OK -- 否 --> CR["重试②：缓存事件；超限可靠转存"]
    CR --> CACHE
    OK -- 是 --> CA["ACK 缓存事件"]
~~~

基础参数错误应在发送前尽早拒绝；Worker 的渲染或媒体永久错误同样直接可靠转存，不进入无限重试。插入、正文、Tag、引用处于一笔事务，准备失败不留半成品文章。

CREATE 重复时核实固定 ID、作者身份与可信命令来源；同作者已有记录也不能直接覆盖。即使现有记录已被修改／隐藏，旧 CREATE 只能补发缓存事件。并发 INSERT 冲突应回滚后重新读，不在失败事务中继续拼接引用。

### 6.2 修改

~~~mermaid
flowchart TD
    A["API：作者鉴权、hidden 和 expectedUpdatedAt 预检查"] --> B["投递 UPDATE；Confirm 后返回 202"]
    B --> W["Worker 先读取当前正式文章"]
    W --> MATCH{"未隐藏且时间戳匹配？"}
    MATCH -- "否，合法目标可确认" --> SKIP["禁止旧写，不改引用；记录冲突或疑似重投"]
    MATCH -- 是 --> PRE["事务外安全渲染"]
    PRE --> TX["RC 事务：锁账户／文章，再次检查状态、权限和时间戳"]
    TX --> CHECK{"锁内仍允许？"}
    CHECK -- "冲突／已隐藏" --> SKIP
    CHECK -- "无权／内容非法" --> FAIL["永久失败，可靠转存"]
    CHECK -- 是 --> SAVE["锁媒体并最终校验；原子替换正文、Tag、引用；更新时间严格递增"]
    SAVE --> COMMIT{"提交成功？"}
    COMMIT -- "失败／未知" --> R["重试①：保持原 expectedUpdatedAt"]
    R --> W
    COMMIT -- 是 --> EVENT["发布 ARTICLE_CHANGED"]
    SKIP --> EVENT
    EVENT --> CONF{"事件 Confirm 且路由成立？"}
    CONF -- 否 --> R
    CONF -- 是 --> ACK["ACK 原 UPDATE"]
    EVENT --> CACHE["共享缓存锁内重读 SQL，失效正／负缓存"]
    CACHE --> OK{"Redis 成功？"}
    OK -- 否 --> CR["重试②：缓存事件"]
    CR --> CACHE
    OK -- 是 --> CA["ACK 缓存事件"]
~~~

两个编辑都携带 T1 时，最多一个事务改写成功。另一个失败为冲突或疑似重投；不保证第二个发出的请求获胜。两个 HTTP 请求先后收到 202，也不代表第一个 SQL 已完成。

若第一次已将 T1 改为 T2，之后事件发送失败，重投看到 T2 只补发事件。没有任务记录，无法区分“自己的第一次成功”与“别人的并发更新”，因此日志不能把这类分支统称为修改成功。

失配时正文、Tag、引用全部不改。禁止自动把 expectedUpdatedAt 换成最新值重试，否则等于绕过冲突保护。

### 6.3 删除

~~~mermaid
flowchart TD
    A["API：检查已存在的正式文章及权限"] --> B["发布 DELETE；Confirm 后返回 202"]
    B --> W["SQL Worker"]
    W --> TX["短事务：账户与文章锁；需要新隐藏时复核权限"]
    TX --> H{"当前状态？"}
    H -- "目标无效／无权" --> FAIL["永久错误，可靠转存"]
    H -- "已隐藏且目标合法" --> EVENT["不改时间戳，补发 ARTICLE_CHANGED"]
    H -- "公开且允许删除" --> SAVE["设置 hidden=true，严格递增 updated_at；保留正文与引用"]
    SAVE --> COMMIT{"提交成功？"}
    COMMIT -- "失败／未知" --> R["重试①：同一 DELETE"]
    R --> W
    COMMIT -- 是 --> EVENT
    EVENT --> CONF{"事件 Confirm 且路由成立？"}
    CONF -- 否 --> R
    CONF -- 是 --> ACK["ACK 原 DELETE"]
    EVENT --> CACHE["缓存锁内重读 SQL，原子设置删除标记、清详情、写负缓存"]
    CACHE --> OK{"Redis 成功？"}
    OK -- 否 --> CR["重试②：缓存事件"]
    CR --> CACHE
    OK -- 是 --> CA["ACK 缓存事件"]
~~~

删除不要求 expectedUpdatedAt：只要有权删除，隐藏当前正式文章。更新先提交则随后删除；删除先提交则迟到更新必须拒绝，最终均为隐藏。

未完成 CREATE 的 ID 没有正式行，DELETE 返回不存在，不代表创建被取消；不能为未知 ID 制造永久墓碑再假设具有撤销能力。V1 不支持取消待执行创建。

## 7. 消息接力、退避与失败恢复

### 7.1 最小拓扑

| 名称 | 作用 |
|---|---|
| article.command.x → article.command.q | CREATE／UPDATE／DELETE 共用命令队列 |
| article.event.x → article.cache.q | article.changed 事件，维护文章详情缓存 |
| article.failed.x → article.command.failed.q | 命令永久失败／重试耗尽／接力未完成 |
| article.failed.x → article.cache.failed.q | 缓存事件处理失败 |

不创建 Feed、统计或 ES 队列。未来 ES 可独立绑定事件，但本次没有对应实现或验收任务。

主队列与失败队列持久化、消息持久化；发送 Confirm + mandatory／Return 检查；消费者手动 ACK。允许流量前声明并核验预期绑定。mandatory 仅证明至少存在路由，未来有多个订阅者时仍需单独核验缓存队列绑定。[RabbitMQ 确认说明](https://www.rabbitmq.com/docs/confirms)

建议两条主队列初期各 Single Active Consumer、prefetch=1、串行监听。旧消费者连接失效后，其业务线程可能仍在运行；不能因此删除 SQL 条件或 Redis token 保护。[RabbitMQ 消费者说明](https://www.rabbitmq.com/docs/consumers)

单节点持久化 Classic Queue 不承诺磁盘／节点丢失后恢复；如需节点容错，应另配置有副本的 Quorum Queue。版本和部署参数在实施时固定。

### 7.2 顺序、ACK 与失败分类

正常次序固定为“SQL 提交／安全跳过 → 发布事件并确认 → ACK 原命令”。不能在 finally 中 ACK，也不能捕获异常只记日志后自动当作成功。

- 同一消息重复执行：固定 articleId、时间戳条件、隐藏终态共同保证不重复写。
- SQL COMMIT 结果未知：保留原命令，重读主库；不能先补偿删除文章或解绑图片。
- 事件确认丢失：允许重复事件；确认不明确不能 ACK 原命令。
- ACK 丢失：允许原消息再次到来；SQL 与缓存均按幂等路径执行。
- deliveryTag 只属于原 Channel；不在重建后的 Channel 上 ACK 旧 tag，初期使用单条确认，避免错误批量 ACK。

建议单次消费最多尝试 3 次，退避 1 秒、3 秒；SQL／网络及总消费耗时均有限，低于 Broker ACK 超时。跨进程重投次数没有持久化记录，不承诺全局严格最多 3 次。

数据库临时故障、死锁、Redis／MQ 网络异常可重试；JSON 非法、越权、媒体归属不符、无效 Tag 等永久错误直接转失败队列。ISSUED 等待 READY 只允许有限短重试，不能无限等待或放宽绑定条件。

失败转存由应用发送原消息及失败阶段、错误码到持久化失败队列；取得 Confirm 且路由成立后才 ACK 原消息。转存失败则保留未 ACK、关闭／暂停当前消费并告警。转存确认丢失可能留下重复失败消息，人工重驱动仍保持原 ID 和条件。

不默认依赖普通 TTL／DLX 转发作为可靠恢复依据；默认死信转发可能在目标不可用时丢消息。[RabbitMQ 死信边界](https://www.rabbitmq.com/docs/dlx)

### 7.3 恢复规则

- SQL 成功、事件未成功接力：原命令重投或从失败队列重驱动，跳过旧正文写入后补发事件。
- 更新冲突：作者重新读取正式内容再决定是否提交；运维不修改旧 expectedUpdatedAt 强行重放。
- Redis 失败：重放事件，只用 articleId，在获得缓存锁后重新读取 SQL。
- 旧 payload 的媒体非法但 SQL 已成功：优先识别已有／过时提交，只补发事件，不再次绑定原文件。
- 失败队列无人处理时可能长期不收敛；必须有告警和明确的重驱动入口。
- MQ 持久化数据永久丢失时，未提交的正文无法从 SQL 还原；已提交文章的缓存可以重建。

commandId 关联阶段日志，不把全文、凭证、Session 或存储密钥写入日志。没有任务表，失败队列和日志是排查依据，不伪装为完整任务状态 API。

## 8. 查询与缓存协调

### 8.1 查询一致性

普通详情可以使用缓存，接受 SQL 提交到事件生效之间的短暂陈旧；编辑与作者管理列表直接读主库，避免拿旧缓存的正文与时间戳继续编辑。

读取详情／编辑 DTO 时，JSON、HTML、封面、Tag 和 updated_at 必须来自同一份数据库视图。可用单条聚合 SQL；若拆成多条查询，使用只读一致快照事务。不能先读旧正文，再读新 Tag、最后拼上新 updated_at，否则用户可能用新令牌覆盖自己从未看见的新正文。只读快照事务不调用媒体引用服务，不与其 RC 写入契约混用。

GET 普通详情对隐藏文章返回 404；编辑读取再次确认作者与状态。GET 不产生浏览／点赞或 MQ 副作用。HTTP 响应建议 no-store，避免浏览器／代理缓存绕过应用删除过滤；服务端 Redis 缓存仍正常使用。

### 8.2 最小 Redis 数据

所有 Key 加项目、环境与文章模块前缀。示例中的 {id} 同时作为按文章归组的 hash tag，便于条件事务涉及的 Key 落在相同槽。

| Key 示例 | 含义 |
|---|---|
| ec:dev:article:v1:{id}:detail | 完整公开详情 DTO，建议 5 分钟加抖动 |
| ec:dev:article:v1:{id}:missing | 已确认不存在／隐藏的短负缓存，建议 10～30 秒 |
| ec:dev:article:v1:{id}:hidden | 隐藏墓碑；V1 不自动过期，不由公开分支清除 |
| ec:dev:article:v1:{id}:cache-lock | 随机唯一 token + 有限租期；回源和失效共用 |

没有列表卡片缓存、Tag 反向索引、排名 Key、快照或统计 Key。未确认的 SQL 异常不写 missing；不存在也不能随意写永久 hidden，以免抢在 CREATE 前阻止正式文章。

### 8.3 共享锁与原子 token 校验

同一文章的以下动作必须共用 cache-lock：

- 持锁读取 SQL 后发布正缓存。
- 持锁读取 SQL 后发布负缓存。
- Cache Worker 清理缓存及设置删除标记。
- 释放锁。

Cache Worker 必须先获得锁，再查 SQL，不能先查出旧状态、等待锁后继续使用旧结果。它仅执行“失效／墓碑维护”，不把查出的正文主动写成新的正缓存。

回源与失效判断使用的 SQL 一致读取视图必须在获得缓存锁之后建立；不能复用获锁前已经形成的旧事务快照或预先准备的 DTO，否则即使锁 token 正确也可能发布过时结果。

Redis 写入使用同连接的条件事务：

1. WATCH cache-lock 及本次需要检查的 hidden 等 Key。
2. GET 核实 token 属于当前调用者；正缓存回填还须确认没有 hidden。
3. MULTI 排入本次全部变更，EXEC 后核对结果；冲突、锁已过期、token 变化均视为没有取得写入资格。
4. 解锁同样检查 token 后条件 DEL，不能 GET 后无保护地 DEL。
5. 不跳过 EXEC 失败，也不在失败后降级为无条件 SET／DEL。

租期内完成只是性能目标，真正的正确性靠条件写，不靠“通常不会超时”。Redis 事务不回滚运行时错误，应固定 Key 类型、检查全部返回；V1 沿用不用 Lua 的实现风格，使用 WATCH + MULTI/EXEC。[Redis 事务说明](https://redis.io/docs/latest/develop/using-commands/transactions/)

### 8.4 回源、失效和隐藏的交错

~~~mermaid
sequenceDiagram
    participant R as 详情请求
    participant K as 文章缓存锁与 Redis
    participant D as MySQL
    participant W as Cache Worker
    R->>K: 获得 token A
    R->>D: 查询正式正文
    D-->>R: 旧正文
    Note over D,W: 新修改已提交，缓存事件到达
    W->>K: 尝试获得同一把锁
    alt A 租期仍有效
        R->>K: 原子验证 A 后回填旧正文
        R->>K: 条件释放 A
        W->>K: 获得 token B
        W->>D: 锁内读取当前状态
        W->>K: 条件清理旧缓存／维护隐藏状态
    else A 已到期
        W->>K: 获得 token B，完成失效
        R->>K: 迟到回填时原子检查 A
        K-->>R: A 已失效，拒绝旧缓存写入
    end
~~~

该协调保证：已处理的失效事件不会再被同一批更早的持锁回源结果覆盖。它不把 SQL 和 Redis 变成同一个事务，也不撤回已经读取／已经发出的旧响应。

详情流程：

1. 检查 hidden、missing，再检查 detail；命中 hidden 直接 404。
2. 未命中则有限等待 cache-lock，获锁后二次检查，再读一致 SQL DTO。
3. 正常公开数据仅在 token 有效且没有 hidden 时回填；SQL 确认不存在／隐藏时发布负缓存，隐藏还可在同一锁内补墓碑。
4. 最终返回前再次检查 hidden；若最终检查不可用，可重新以受控 SQL 校验后返回，不把 Redis 错误解释为可见或不存在。
5. 拿不到锁可短暂重查，必要时受限并发直接回源，但无锁结果不写共享缓存。
6. 编辑接口直接读 SQL；普通详情回源失败返回依赖错误，不写负缓存。

Cache Worker 在锁内查主库并执行：

| 当前状态 | 操作 |
|---|---|
| 正式公开文章 | DEL detail 和 missing；不主动删除永久 hidden；正常状态下不应同时存在墓碑 |
| 正式隐藏文章 | 原子设置 hidden、清 detail、写短 missing |
| 不存在 | 清 detail、写短 missing；不制造永久墓碑 |
| SQL 失败／锁超时／EXEC 冲突 | 本事件重试，不 ACK 为成功 |

公开 SQL 与 hidden 墓碑冲突属于异常，需要受控修复；不能用迟到的公开事件随意清除隐藏标记。SQL 后来再次变化时，由后续事件继续收敛。

### 8.5 Redis 恢复

V1 接受维护窗口恢复：暂停文章缓存写入／消费者，隔离旧实例和未完成写入，清理本模块详情及负缓存，从 SQL 重建墓碑，恢复消费并修复失败队列。必要时切换文章缓存命名空间隔离旧写入。

恢复期间普通详情可受控查主库；现有登录认证依赖 Redis 的行为不被文章降级绕过。不清空共享 Redis，不删除认证或其他环境 Key。删除标记无自动 TTL，不代表 Redis 丢失后仍能靠空标记保证隐藏。

## 9. 竞态目录：时序、处理和剩余边界

本节是实施与评审重点。每项都需要通过可控屏障／故障注入验证，不用随机 sleep 碰运气。

### 9.1 受理、并发命令与 SQL

| 编号 | 危险交错 | 必须处理 | 仍需明确的边界 |
|---|---|---|---|
| R01 | Broker 接收命令，Confirm／HTTP 响应丢失，用户再次 POST | 同一 MQ 命令复用 articleId；API 不虚报未执行；前端不自动无限重发 | 新 HTTP 分配新 ID 仍可能重复创建，未承诺端到端去重 |
| R02 | 两个 Worker 同时看到 CREATE 目标不存在，再同时 INSERT | 主键约束；一笔提交；冲突者整笔回滚后读取已有行，只补事件 | 不能捕获主键异常后在半失败事务里继续绑定 |
| R03 | CREATE 成功后已有编辑／删除，旧 CREATE 再投 | 固定 ID、身份检查、已有记录只补事件 | 不按旧 payload 覆盖当前内容，不清除 hidden |
| R04 | 两条 UPDATE 都基于 T1，先后提交 | 文章行锁＋锁内时间戳检查，最多一条写入；其引用与 Tag 同步受保护 | 单活消费也可能有两条基于同一旧编辑页的命令 |
| R05 | 用户等到第一次 202 就提交第二次修改，仍拿 T1 | 202 不当成 SQL 完成；后续更新须重新读正式正文核对 | 不能保证“最后一次发出”的修改胜出 |
| R06 | 渲染期间他人更新，事务前检查过而事务内未复核 | 渲染后锁文章再次核对 expectedUpdatedAt，失配整体不写 | 事务外准备结果不是写入许可 |
| R07 | UPDATE 与 DELETE 任意顺序／重复投递 | 删除终态；锁内拒绝 hidden 更新；删除不依赖旧时间戳 | 删前已发出的旧响应可以完成，非零延迟撤回 |
| R08 | CREATE 尚排队，用户拿返回 ID 立即 DELETE | 对尚无正式行返回不存在，不制造永久墓碑 | 本版不提供撤销待创建任务 |
| R09 | COMMIT 已成功但连接中断，Worker 当成失败重投 | 重读主库；已有／失配分支不重复写，仍补事件 | 不做未经确认的“补偿删除／解绑” |
| R10 | SQL 已提交；原 payload 某图片后来移除／回收；重投先校验旧图 | 先识别已有／过时提交，只读补发不再绑定旧图 | 否则一次成功 SQL 会被后续媒体错误阻断修复 |
| R11 | API 预检账号正常，排队期间账号被注销 | 新写入时事务内复核可用状态；共享账户锁与注销互斥锁明确排序 | 注销不自动隐藏所有既有文章，退出／改密也不是任务取消 |
| R12 | 两条细粒度 SQL 分别替换正文与媒体，第二条失败 | 内容、Tag、引用、令牌同事务；异常全部回滚 | 禁止 REQUIRES_NEW 拆开关联事务或捕获错误继续提交 |

### 9.2 媒体与查询视图

| 编号 | 危险交错 | 必须处理 | 仍需明确的边界 |
|---|---|---|---|
| R13 | 事务外看到 READY，绑定前 GC 已将文件转 DELETING | 与现有 GC 使用同一媒体行锁，锁内重查状态和归属 | GC 先胜出则文章失败，不恢复已删除文件 |
| R14 | GC 扫描到无引用候选，文章先锁文件并成功绑定 | GC 获锁后重查引用及当前到期时间，不能用旧扫描结果删图 | 依赖现有媒体服务契约，文章不能绕开它直接插引用 |
| R15 | 两篇文章按相反顺序绑定相同文件，或同文章封面与正文重复 | 先文章后按文件 ID 排序锁；集合去重；复合主键防重复 | 不无条件增加 ref_count；发生死锁整事务重试 |
| R16 | 消息排队超过 24 小时无引用宽限，文件被回收 | 消费时安全失败，允许重新上传 | 未做临时占用，不能承诺长期排队保图 |
| R17 | 编辑去掉图片，但旧缓存／在途响应还引用它 | 解绑只重新安排 GC 宽限，不立即物理删除；缓存寿命／请求耗时有上限 | 对象 URL 的历史访问不永久保证；隐藏继续保留引用 |
| R18 | 编辑查询先读旧正文，再读新 Tag 和新 updated_at | 聚合 SQL 或只读一致快照，令牌与整份正文匹配 | 编辑绕过公共缓存；不能拼成从未存在过的 DTO |

### 9.3 缓存、消息接力与故障转移

| 编号 | 危险交错 | 必须处理 | 仍需明确的边界 |
|---|---|---|---|
| R19 | 回源先读旧正文，事件清缓存后回源再 SET | 回源与失效共用缓存锁；token 条件写；失效后不允许旧锁回填 | 不是简单 DEL＋TTL，也不能只让读请求之间共用锁 |
| R20 | 回源认为不存在，CREATE 清负缓存后旧请求再写 missing | 正、负回填与失效同一锁及 token 规则 | 否则“创建成功但一直 404”可被反复续负缓存 |
| R21 | 持锁线程卡顿，租期过期，新线程完成失效，旧线程恢复 | WATCH 后核对 token，EXEC 验证；旧 SET／DEL／解锁被拒绝 | 锁过期不会终止 Java 线程，不能只在方法入口检查 |
| R22 | Cache Worker 先读公开 SQL，再长时间等锁，随后写旧投影 | 获得缓存锁之后重新读 SQL；事件只存 articleId | 读当前 SQL 本身不防“查旧后晚写”，还需协调写入资格 |
| R23 | 公共缓存已命中旧详情，删除标记随后建立 | 返回前重查 hidden；新请求见标记拒绝 | 最终检查之后／已下发的响应无法撤回 |
| R24 | Redis 操作已执行，但成功响应丢失 | 同一事件重试、重新查当前状态；不无条件清除 hidden | 重复删除／设置固定墓碑安全，允许重复失效 |
| R25 | SQL COMMIT 后、事件发出前崩溃；或事件已发出但 ACK 前崩溃 | 只有下一阶段 Confirm／路由确认后才 ACK，重投安全跳过并补事件 | 原命令被永久丢弃或无人处理的失败队列会破坏最终收敛 |
| R26 | 原消息可靠转存失败队列时 Confirm 丢失／目标不可用 | 转存确认前不 ACK；结果未知允许重复失败消息 | 普通 DLX 不是天然可靠替代；人工重驱动保持原条件 |
| R27 | 单活消费者断联，新实例接管，旧实例仍在执行 SQL／Redis | SQL 主键、行锁、expectedUpdatedAt 和隐藏终态；Redis 随机 token 条件写 | SAC 不提供业务线程 fencing；不能靠 prefetch=1 省掉防护 |
| R28 | Channel 重建后复用旧 deliveryTag，ACK 到别的消息 | deliveryTag 与原 Channel 绑定；单条 ACK；旧 Channel 的确认失败按重投处理 | 错误 ACK 可能丢任务，属于实现必测项 |
| R29 | Redis 重启／恢复旧备份、墓碑丢失、旧线程继续写 | 暂停并隔离写入，恢复期查主库，失效旧缓存并重建墓碑，再消费积压 | 不把空墓碑当作公开；不在旧写入仍活跃时盲目恢复 |
| R30 | 滚动发布时旧 JSON 被新渲染规则解释成不同正文 | 消息带 schemaVersion，明确兼容节点与属性；不支持则失败转存 | 渲染确定性以协议版本为前提，不静默降级为另一种格式 |

R19／R20／R21 是本次相对上一版的重点加强：**共享缓存锁＋写入时 token 校验**直接约束旧回填，不再只把竞态留给短 TTL。

## 10. 验收与故障注入计划

### 10.1 必要测试分组

| 分组 | 可控验证方式与断言 |
|---|---|
| SQL 原子性 | 在文章、Tag、媒体引用任一保存点注入异常，断言整笔回滚，没有部分正文／引用 |
| 更新冲突 | 两线程同持 T1，在锁前用屏障对齐；断言最多一笔改写，落库正文与引用同源 |
| 修改与删除 | 分别强制“更新先提交”“删除先提交”；断言最终隐藏，旧更新不复活 |
| CREATE 重投 | 在 INSERT 前对齐重复命令、提交后再重复旧命令；断言只有一行且不覆盖后续编辑 |
| COMMIT 与事件接力 | 在 COMMIT 后、事件 Confirm 前、ACK 前分别终止消费者；恢复后断言只补投影，不重复业务写 |
| 成功后媒体变化 | 首次提交后移除原图／改变当前引用，再重驱动旧命令；断言事件仍能补发 |
| 账户注销 | 分别控制共享锁／注销互斥锁先后；验证约定的接受与拒绝边界 |
| GC 与绑定 | 在 GC 扫描后阻塞，再让文章绑定；反向再测；断言 GC 重查且不引用 DELETING 文件 |
| 正／负旧回填 | 暂停 SQL 返回与 Redis 回填之间，插入缓存失效／CREATE；验证共享锁和条件写阻止迟到覆盖 |
| 锁过期与旧解锁 | 让 A 过期、B 取得锁，恢复 A；断言 A 的 SET／DEL／unlock 都不能改动 B |
| 查询一致视图 | 在正文／Tag 读取间提交一次更新；断言 DTO 全部旧或全部新，不混合新令牌和旧正文 |
| 缓存与 Channel 故障 | 注入 EXEC 冲突、Redis 响应丢失、Channel 重建、失败转存确认失败；断言不提前 ACK |
| Redis 恢复与协议升级 | 文章命名空间恢复后不曝光隐藏正文；未知 schemaVersion 不破坏正式内容 |

并发测试使用 latch／barrier 和可控假依赖暂停关键步骤，不靠固定 sleep 证明正确。锁过期可控制时间或明确等待真实 Redis 租期，并设置总超时。崩溃、真实 ACK、连接断开与行锁边界必须用专用集成环境验证，单元 Mock 不能替代。

### 10.2 观测与验收口径

最小指标：主队列 ready／unacked／最老积压时间、Confirm 超时、失败队列数量与阶段、编辑冲突、媒体校验失败、渲染耗时、回源锁等待／丢失、SQL 超时、缓存条件提交失败及恢复结果。

日志关联 commandId、articleId、operation、phase、expectedUpdatedAt；区分“SQL 实际改写”“跳过旧写”“事件补发”“缓存已收敛”，不将一次 ACK 当作用户修改成功证明。

本阶段完成意味着：CRUD 接口可用，正文与引用原子，消息重复及典型乱序不破坏事实，SQL→缓存接力可恢复，关键竞态有确定性验证。不是“所有命令无需人工干预最终必成功”。

## 11. 分批实施计划

| 批次 | 本批交付 | 重点竞态／验收 | 建议提交消息 |
|---|---|---|---|
| 1 | 文章／Tag 最小模型、输入契约、JSON 白名单渲染、固定 ID 与时间令牌规则 | 参数不可伪造、协议版本、微秒传输、渲染失败无副作用 | feat(article): 建立文章模型与内容校验契约 |
| 2 | 正式文章 SQL 创建／修改／隐藏、账户事务读取、Tag 和媒体集合原子维护 | R02～R18，尤其并发编辑、删除终态、GC 与账号注销 | feat(article): 实现文章事务与并发写入保护 |
| 3 | RabbitMQ 命令／事件接力、Confirm、手动 ACK、退避、可靠失败转存和重驱动 | R01、R09、R10、R25～R28；COMMIT 后崩溃恢复 | feat(article): 实现异步命令与可靠事件接力 |
| 4 | HTTP 增删改查、编辑读取、作者管理查询；详情正负缓存、共享缓存锁和墓碑 | R18～R24、R29；编辑直读、旧回填与过期锁防护 | feat(article): 接入文章接口与缓存并发协调 |
| 5 | 专用环境故障注入、权限与恢复联调、接口／部署文档 | 覆盖竞态目录、恢复手册、失败消息重驱动和 schemaVersion 升级 | test(article): 验证文章异步竞态与故障恢复 |

每批可继续拆小；先内部事务及渲染测试，再向 HTTP 暴露异步语义，不临时发布与目标契约不同的同步写接口。测试跟随实际批次加入，第 5 批是完整故障验收，不把并发测试全部拖到最后。

按现有分包放入 article.web、article.application、article.domain、article.infrastructure.persistence／messaging／cache，只有实际需要时才加 scheduling；本次无排名／统计定时任务。跨业务只通过 user.application、media.application；中文注释说明前提与关键检查，不重复堆叠上游已保证的纯格式校验。

每批执行相关测试，最终按仓库约定 mvn clean verify。真实 SQL、Redis、RabbitMQ 测试必须专用环境和命名空间，不清理业务库／共享 Redis。本次修订只改设计稿，未执行这些尚未实现的业务测试。

## 12. 待审核决定与明确延期

| 待审核项 | 建议及影响 |
|---|---|
| HTTP 创建结果未知后的去重 | 暂接受限制，或先确定可复用创建凭据／最小持久化去重；不声称已有 commandId 就解决 |
| 异步结果反馈 | 暂不建任务表，只核对正式状态＋失败队列运维；若要精确告知每次请求成功／失败需另定方案 |
| ID 生成 | 服务端预分配正 BIGINT，确认生成器、多实例 workerId 和时间回拨策略 |
| 渲染实现 | Java 受限节点白名单或 Node 复用 Tiptap 扩展，先固定支持的节点与 schemaVersion |
| 权限 | 作者自管为基线；管理员／巡查员代删及游客 GET 是否开放另确认 |
| 账号状态竞争 | 新写入建议账户共享锁排序；注销不批量隐藏旧文章；退出／改密不自动撤销队列命令 |
| 缓存协调 | 同一文章回源／失效共享锁，使用原子 token 条件写；不引入文章版本或缓存代际表 |
| 初始参数 | 消息 1 MiB、Tag 5 个、详情缓存约 5 分钟、负缓存 10～30 秒、单次消费 3 次尝试；锁租期按调用预算确定 |
| 恢复与可见性 | 接受异步短暂旧读、在途旧响应、维护窗口恢复及失败队列人工重驱动 |

**延期清单：信息流、Tag 排序、浏览量、点赞及其表／缓存／定时任务全部移出本次计划。** 未来另立设计稿，不把旧版推荐公式、快照参数和排序接口留作本次隐含验收项。

参考：[RabbitMQ Confirm／ACK](https://www.rabbitmq.com/docs/confirms)、[消费者与单活](https://www.rabbitmq.com/docs/consumers)、[失败转移安全边界](https://www.rabbitmq.com/docs/dlx)、[Redis 条件事务](https://redis.io/docs/latest/develop/using-commands/transactions/)、[媒体引用服务](../../../../backend/src/main/java/com/febrie/backend/media/application/reference/MediaReferenceService.java)、[媒体建表定义](../../../../backend/src/main/resources/db/migration/V2__create_media.sql)。
