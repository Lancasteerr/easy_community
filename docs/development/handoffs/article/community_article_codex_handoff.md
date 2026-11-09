# 技术社区文章子系统交接文档（交给 Codex）

> 状态：设计决策已收敛，按本文实现。日期：2026-10-31。  
> 目标：以**同步短事务完成文章 CRUD**，利用 MySQL 保证核心数据一致；Redis 只负责读缓存和信息流，定时任务负责维护；**本阶段不引入 RabbitMQ/文章操作状态机/额外分布式锁**。

## 1. 项目背景与确定的取舍

- 技术栈：Java + Spring Boot、MyBatis/MyBatis-Plus、MySQL（InnoDB）、Redis、TipTap JSON、MinIO（开发）/ COS 或 OSS（生产的 S3 兼容适配层）。
- 用户角色：普通作者、巡查员（可隐藏文章/评论，不管理账号）、管理员、游客；身份取自服务端已验证会话上下文，**不得信任请求传入的 authorId/role**。
- 文章支持创建、编辑、隐藏式删除；详情缓存；标签；浏览量、点赞量；首页与 Tag 信息流。
- 富文本：编辑数据为 TipTap JSON；后端生成并保存安全 HTML；详情直接读取预渲染 HTML。图片节点引用内部 `fileId`，最终图片 URL 必须由后端根据可信文件元数据确定，禁止直接信任前端的任意 HTML、`src` 或 CSS/事件属性。
- 文件上传/审核是独立能力。文件完成审核并进入公开持久区后为 `READY`，且公开 URL/对象 Key **不可变**；清理文件时进入 `DELETING` 后禁止重新绑定。
- **接受同一文章“最后成功提交的修改覆盖之前修改”**：当前不做文章编辑乐观锁 `version`、合并编辑或实时协作；但隐藏删除是终态，不能被迟到的编辑恢复。
- 文章删除是 `hidden` / `is_hidden` 软删除，**不解除媒体引用**；日后若做彻底删除，另行设计。
- MySQL 是文章、文件状态、文件引用和权限判定的权威来源。缓存、信息流、未来 ES 允许有界的最终一致性，不追求 MySQL 与 Redis 的跨系统强事务。
- 当前不需要 MQ：HTML 转换是事务外同步预处理；文章核心写入是同步 SQL；信息流排名与文件 GC 可以用已有定时任务。未来引入 ES 时再选择同步写入、增量扫描或 MQ + Outbox，**并非用了 ES 就必须 MQ**。

## 2. 核心不变量（不能被实现破坏）

1. **合法性与最终写入在一个 MySQL 事务内决定**：文章当前存在、作者/权限、未隐藏状态、目标文件 READY/用途/归属、引用更新、Tag 更新、JSON/HTML 更新应当在同一事务内完成或全部回滚。
2. 修改与删除必须通过**相同文章行**的 InnoDB 行锁或条件 UPDATE 串行化；已隐藏文章不得继续被编辑。
3. 每次文章文件引用变更须先锁定所涉 `media_file` 行；建议这类提交事务及 GC 的认领事务使用 `READ_COMMITTED`，保证锁后普通引用查询读取最新已提交结果，避免混用旧快照；GC 同样先锁文件行、检查真实引用、提交 `READY -> DELETING`，之后才能在事务外物理删除对象。所有绑定入口（文章、头像、以后评论）都要遵守同一约定。
4. `media_reference` 以 `(file_id, ref_type, ref_id)` 唯一约束去重；从 TipTap JSON 提取的文件 ID 必须先去重。引用是**集合关系**，不是“每次消息 +1”的计数逻辑。
5. **Redis 锁只协调同一文章的详情缓存操作**（回源填充、失效、负缓存、删除标记），不跨越 MySQL 事务，也不代替数据库行锁。
6. 物理文件删除、Redis、MQ、ES、对象存储网络调用都不得在文章核心数据库事务内部执行。
7. 任何失败不能造成“已提交部分文章，但引用/Tag 未提交”的情况；数据库事务失败回滚。**数据库提交后缓存失败，不得假装 SQL 已回滚**。

## 3. 建议数据结构及索引

在仓库现有表上最小改造，不要求照搬字段命名：

- `article`：`id BIGINT AUTO_INCREMENT PRIMARY KEY`、`author_id`、标题/摘要、`content_json`、`content_html`、封面相关字段、`is_hidden`、创建/更新时间（以及原有浏览/点赞元数据）。**暂不新增内容版本号、pending 状态、operation_id 或任务表。**
- `article_tag`：保证 `(article_id, tag_id)` 唯一；Tag 热门/最新由信息流索引处理。
- `media_file`：复用既有 `id`、`uploader_id`（或能可信证明上传归属的字段）、`purpose`、`object_key`、`public_url`、`status`、`unreferenced_at`、维护/重试字段。`READY` 时公开对象 Key/URL 必须不可变。
- `media_reference`：`file_id`、`ref_type`（如 `ARTICLE_CONTENT`, `USER_AVATAR`）、`ref_id`；唯一索引 `(file_id, ref_type, ref_id)`，另有利于按 `(ref_type, ref_id)` 读取和删除的索引。
- GC 按状态、回收时间筛选需要索引；具体名称与 SQL 以现有仓库为准。
- 当前无需 `article.version`。如果将来需要防止用户长时间编辑覆盖或需要可靠 ES 消息乱序控制，再独立评估版本/变更序列；**不要悄悄把版本冲突校验加回本阶段的 CRUD**。

### MySQL 自增 ID 获取

`INSERT article` 后通过 MyBatis `useGeneratedKeys=true`、`keyProperty="id"`（注解 `@Options(useGeneratedKeys=true, keyProperty="id")`）获得生成的 `article.id`；同一事务内拿此 ID 建立 `media_reference` 和 Tag。后续步骤失败则整个事务回滚，自增 ID 有间隔正常，**绝不使用 `SELECT MAX(id)`**。

## 4. 文章创建（同步完成）

**事务外预处理：**

1. 从认证上下文读取 userId；检查输入大小、Tag 数、图片数、TipTap 节点类型与 JSON 深度；提取并去重 `fileId`。
2. 按 fileId 批量回查必要的公开 Key/URL（**此处不是最终有效性校验**）。严格把 TipTap JSON 转为安全 HTML：节点/属性白名单、URL 方案与域名约束、禁止脚本/事件属性；HTML 和 JSON 使用同一份规范化内容。不得根据客户端提交的任意图片 URL 渲染。
3. 若预处理需要文件的 Key/URL，假定 `READY` 文件公开 Key/URL 不可变；即使 GC 在预处理期间改成 `DELETING`，后面的事务仍必须拒绝绑定。

**事务内：**

1. `INSERT article` 获取自增 id（事务未提交前其他普通读事务看不到此文章）。
2. 按稳定顺序锁定待绑定文件行（`SELECT ... FOR UPDATE`；批量查询须注意实际加锁顺序），逐个核验文件齐全、`READY`、用途正确、上传者归属正确；缺一即回滚。
3. 批量建立 `media_reference`、文章 Tag，维护必要的 `unreferenced_at`；提交文章 JSON、HTML、元数据。**所有 SQL 使用同一 Spring 事务和连接**。
4. `COMMIT` 成功之后才进行 Redis 缓存失效/信息流衍生操作，最后返回 `articleId`。

**失败语义：**事务失败不返回成功；重复 HTTP 创建请求暂不保证只创建一篇（如果产品要求请求幂等，未来加 idempotency key，不需要为此上 MQ）。

## 5. 文章更新（接受最后写入覆盖）

**事务外：**与创建一致的 JSON 规范化、fileId 提取、批量 URL 回查、安全 HTML 生成。避免在事务里做高耗时解析。

**事务内：**

1. 按文章主键 `SELECT ... FOR UPDATE`，从数据库当前状态校验文章存在、未隐藏、作者有权编辑（管理员编辑权限若未定义，不擅自新增）。
2. 查询当前文章旧引用与待提交的新引用集合；锁定需要维护的旧、新 `media_file` 行，按 ID 固定顺序获取行锁。
3. 对**新增绑定的文件**校验 `READY`、用途、上传者归属和存在性；如现有引用文件因历史异常处于其他状态，应按业务规则报错/记录，不能静默破坏引用。`old - new` 引用解绑，`new - old` 绑定，必要的无引用回收时间在同一事务内更新。
4. 更新正文 JSON、HTML、标题、摘要、Tag、封面、更新时间；提交。
5. **不使用 `expectedVersion`**。多个编辑事务按行锁依次提交，后提交的完整版本覆盖前一版本；不做字段级自动合并。若一个编辑先拿到行锁、删除随后执行，则先更新后隐藏；反过来删除先隐藏，编辑必须失败。
6. 事务提交后进行详情缓存失效和信息流元数据更新，返回文章 ID/成功。

避免先在事务外校验作者/文件 READY 后直接写入；事务外校验只能用于提前失败，**最终校验必须重新执行于事务内**。

## 6. 文章删除（隐藏）

- 删除权限：作者本人，以及具备删文权限的管理员/巡查员；权限来自认证上下文。
- 最小 SQL 可使用一条权限受控、状态受控的条件 UPDATE（如作者删除：`UPDATE article SET is_hidden=1, updated_at=NOW() WHERE id=? AND author_id=? AND is_hidden=0`），由 InnoDB 更新锁与编辑事务自然串行。
- **不解除任何文件引用，不物理删除文件，不在事务内访问 Redis。**
- 影响行数 1 表示本次成功隐藏；0 表示不存在、无权或已隐藏，需按业务规则辨别 403/404/已删除时的幂等返回。巡查员/管理员同样须在最终写入时强制权限条件或处于受保护事务内。
- 提交后持有同一文章的**缓存操作锁**进行：删除正缓存、写负缓存/隐藏标记，更新信息流删除过滤；必要的 Redis 修改尽可能一段 Lua 原子执行。缓存更新失败单独记录并重试/靠 TTL 兜底，不倒转 SQL 结果。
- 软删除是终态：任何迟到的修改或后台缓存失效都不得清除隐藏标记或恢复可见性；未来“恢复文章”是另一个显式业务能力。

## 7. 文件 GC 与引用绑定的竞态协议

**绑定路径（所有使用文件的业务）：**先锁 `media_file` 行并确认 `READY`，再建立引用及维护无引用时间，事务提交。文章更新同时持有文章行锁。

**GC 路径：**定时扫描候选文件（可无锁读取候选 ID），逐个/小批进入短事务；先锁对应 `media_file` 行，**在获得锁后**重新检查 `status = READY`、`unreferenced_at` 宽限期、`NOT EXISTS(media_reference)`；满足才原子更新 `READY -> DELETING` 并提交。事务外执行物理对象删除，失败由已有维护重试机制处理。

这确保：
- 绑定先持锁并提交引用，则 GC 随后看到引用，不允许标记删除。
- GC 先持锁并提交 DELETING，则后续绑定发现不是 READY，拒绝。
- 其他业务（头像）不能绕过文件状态门禁；不允许应用以外的 GC 程序直接在对象存储中删除仍可绑定的 READY 文件。
- 软删除文章不解除引用，故软删除不会让它的图片变孤儿。
- SQL 行锁不消除死锁的可能，尽量稳定加锁顺序、限制锁范围，并在数据库死锁时**重试整个短事务**（有上限与退避），不要在半失败事务内继续操作。

## 8. Redis 详情缓存：统一的“缓存操作锁”

### Key 逻辑（命名按项目已有习惯调整）

- `article:detail:{id}`：正缓存或短 TTL 负缓存（或分开的 negative key，二者择一；避免两套矛盾状态）。
- `article:cache-lock:{id}`：同一文章的缓存操作锁，`SET NX PX`，值为唯一 token；必须通过 Lua 比较 token 后释放。
- `article:deleted:{id}`：已隐藏删除标记，用于保护读路径与信息流过滤；**只由真正删除/显式恢复业务维护，普通更新失效不得删除它**。标记 TTL 与详情缓存/推荐快照留存周期协调。

### 读取

1. 先检查删除标记，再读正/负缓存；命中直接返回，无需加锁。删除标记命中返回不存在。
2. 未命中者抢缓存操作锁。拿到后**再次检查删除标记和详情缓存**（双检），仍未命中才回源 MySQL（`is_hidden=0`）并写正缓存或负缓存。
3. 缓存写入使用 Lua 原子校验**当前 token 仍为自己的锁 token**，并确认无删除标记，再写缓存、设置 TTL。锁过期的旧请求不可回填，不能只在 Java 里检查后单独 `SET`。**写请求对缓存的删除/负缓存/删除标记操作，也要在 Lua 中原子校验锁 token。**
4. 未拿到锁的读取可短暂等待并重试缓存；超时后降级到 SQL 直接读取但**不得绕过锁写缓存**。缓存故障策略需限制 DB 回源并发。

### 增/改/删后的缓存操作

- **所有写业务先提交 SQL，再争抢同一缓存操作锁进行失效，绝不把 Redis 操作放进文章 SQL 事务。**
- 创建：删除该新 ID 可能被提前探测形成的负缓存；不清理无关删除标记（自增 ID 不复用）。
- 更新：删除旧正/负详情缓存；**绝不能移除删除标记**（防止“先更新后删除，但更新失效动作迟到”的删写竞态）。
- 删除：删除详情缓存并写入负缓存 + 删除标记（最好同一个 Redis Lua 脚本）；完成后释放锁。删除标记优先于任何普通正缓存。
- 锁 TTL/执行中断意味着它不是绝对强一致；token 校验防止失效旧持锁者乱写。SQL 提交到缓存失效之间也存在短暂窗口，项目明确接受有界最终一致性；Redis 失败要告警、有限次重试，缓存 TTL 与周期校准兜底。不要声称“负缓存本身天然解决所有旧回填竞态”。
- 后续如要求删除后全局立即不可见，必须进一步加强跨系统协调或强制权威状态校验；当前需求不保证这一点。

## 9. 首页 / Tag 信息流与计数（现有设计衔接）

- 首页全局信息流按推荐分排序；Tag 信息流支持热门和最新。推荐分可基于近 7 天浏览量/点赞量和发布时间计算，具体权重可配置。浏览量、点赞量沿用既有 EasyBlogFull 的 Redis 缓存与周期刷盘思路。
- 推荐排序和 Tag 最新排序使用 Redis ZSET / 版本化快照，后台**定时**批量刷新，不要求每次文章 CRUD 同步重新计算全局得分，也不需要 MQ。
- 为防无限滚动翻页时跨快照重复/漏项，第一页固定 `snapshotVersion`，后续游标携带快照版本和排序位置；旧快照保留一段 TTL。**新文章通常在下次刷新/下次开新会话时出现**，不承诺在同一个固定快照中实时插队。快照过期要给前端明确重置语义。
- 删除后 Redis 删除标记应让详情读和信息流结果过滤隐藏文章；在投递/清理排名索引失败时，详情 SQL `is_hidden` 仍为权威事实。列表可适量超取补位并在维护任务中移除无效 ID。
- 正文保存和浏览量/点赞量计数分别设计，不要让异步统计回写覆盖正文、Tag 或隐藏状态。

## 10. 不引入 MQ 的边界、未来接入 ES 的路线

**当前：**

- HTML 转换和安全过滤：事务外同步执行，限制输入大小和计算成本。
- 文章和文件关系：在一个 MySQL 短事务中同步落库。
- Redis 详情失效：事务提交后尝试；失败重试/TTL 兜底。
- 排名刷新、文件 GC、计数刷盘：沿用定时任务，无需为 CRUD 强行拆出两个 RabbitMQ 阶段。

**未来接入 Elasticsearch：**

- ES 索引本身是 MySQL 的**可重建投影**，文章详情仍以 MySQL 为准。
- 小规模可先用定时全量重建/增量对账；需要低延迟与可靠变更传播时选 **MySQL Transactional Outbox + MQ/CDC**：核心文章事务内顺便写事件，事务外发布、消费重试和幂等。`@TransactionalEventListener(AFTER_COMMIT)` 不是“消息一定不会丢”的保证。
- 因本阶段故意不加文章内容版本，未来 ES 异步事件可能乱序。应在接入 ES 时增加持久化递增的变更序列/版本，并统一处理更新、删除的先后关系，或采取可证明正确的单实体顺序消费与定期对账。不要仅凭 MQ “按顺序消费”就认为不会旧索引复活；Elasticsearch 的 external versioning 可用于处理乱序索引，但删除与延迟事件的处理也需设计墓碑和对账。
- 若实际出现昂贵审核或 HTML 转换，再讨论异步不可变修订/提交任务；这不是首期实现内容。

## 11. Codex 实施要求（不要擅自升级架构）

1. **先检查项目现有代码和表结构**，复用模块、mapper、DTO、事务管理器、Redis Key 约定以及文件维护任务，不凭空重建相同能力。
2. 将 `prepareArticle`（事务外）与 `commitCreate/commitUpdate`（`@Transactional` 的独立 Spring Bean）分开；避免同类方法 self-invocation 使 Spring 事务代理失效。事务实际生效范围必须有测试证明。
3. MyBatis 创建返回自增 ID；不要在事务外持久化草稿状态，不引入 `pending_json`、article operation 状态机、额外分布式锁、MQ、Outbox、强制编辑版本号。
4. 缓存锁严格只保护缓存操作，含 token 比较释放和写入；删除标记永不被普通更新的延迟清缓存逻辑清除。
5. 为创建、更新、软删除、文件绑定/GC 的竞态写并发集成测试；不得只靠 Mock 测试事务/行锁。
6. CRUD SQL 不调用对象存储、Redis 或任何远端网络服务；允许访问已在事务外准备的数据。事务内仅进行必要 SQL 和快速内存校验。
7. 先实现文章 CRUD 和详情缓存，再衔接 Tag、信息流刷新、计数统计；不为未来 ES 提前增加队列组件。
8. 所有新增异常和响应码应延续项目现有风格。提交成功但缓存失效失败，应保留准确的业务结果并记录可观测故障，不让前端误以为文章没保存从而盲目重试创建。

## 12. 必须通过的测试矩阵

| 并发/故障情境 | 期望 |
| --- | --- |
| 两个线程修改同一文章，均有权限 | 事务串行，不破坏引用；最后成功提交的完整文章获胜 |
| 编辑和隐藏删除竞争，编辑先拿行锁 | 编辑提交后隐藏成功；最终隐藏 |
| 编辑和隐藏删除竞争，删除先拿行锁 | 删除成功；编辑检查隐藏状态后回滚 |
| 多个删除同时到达 | 最多一次状态从可见变隐藏，按定义返回结果 |
| 创建过程中文件校验失败 | article、Tag、media_reference 全部回滚 |
| 文章绑定文件与 GC 同时运行 | 绑定成功则 GC 不删除；GC 先置 DELETING 则绑定失败 |
| 图片/fileId 非本用户、非 READY、用途不符 | 整个文章提交失败，无部分引用 |
| 同文章 JSON 内同一图片出现多次 | media_reference 仅一条对应引用 |
| 更新文章替换图片 | 旧引用解绑，新引用绑定，无引用文件只在宽限期后进入 GC |
| 隐藏文章 | 文件引用全部保留，详情接口不再返回文章 |
| 回源查询旧内容时文章已隐藏 | 后续删除锁操作移除旧缓存；过期旧持锁线程无法覆写删除标记 |
| 先更新后删除，更新缓存清理晚于删除标记 | 更新不得删除 tombstone，不得让文章重新可见 |
| Redis 不可用但 SQL 事务提交成功 | 不会伪装为数据库回滚；有监控/重试/TTL 等降级行为 |
| SQL 回滚/死锁 | 不写入缓存成功状态；死锁重试整个事务且有次数上限 |
| 事务生效性 | 不同 Mapper 的 SQL 全部在同一事务内；失败时全回滚 |
| 重复 HTTP 创建请求 | 当前可能创建两篇，明确是接受的取舍，不宣称幂等 |

## 13. 技术参考（官方）

- MySQL InnoDB locking reads: https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html
- MySQL transaction isolation: https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html
- MyBatis generated keys: https://mybatis.org/mybatis-3/zh_CN/sqlmap-xml.html
- Spring `@Transactional` / 代理调用限制: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Redis cache-aside: https://redis.io/docs/latest/develop/use-cases/cache-aside/
- Redis lock token / TTL caveat: https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/
- AWS Transactional Outbox: https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
- Elasticsearch index external versioning: https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-index

**交付原则：优先最少状态、最少附加锁、明确 SQL 原子边界；发现现有代码与本文假设冲突时，先定位差异并说明，不要默默实现一套更复杂的系统。**
