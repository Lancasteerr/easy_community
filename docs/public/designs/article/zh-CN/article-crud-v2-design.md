# 文章增删改查与缓存一致性 V2 设计方案

> 2026-11-11更新：本文保留历史设计背景；本人SQL分页、旧游标及索引字段约定已由[统一查询v2契约](../../../operations/search/zh-CN/article-search-operations.md)替代。当前公开作者搜索与本人独立接口共用ES候选链路，均仅展示公开文章。

> 状态：已实现，验收记录见最终接入文档。更新日期：2026-11-01。
>
> 最终实施契约见 [文章 CRUD 接入与恢复](../../../guides/article/zh-CN/article-crud-implementation.md)。本文与最终契约共同描述当前实现：删除不可恢复，作者管理列表只展示公开文章；不实现隐藏条目私有装配、回收站或恢复接口。游客仅开放文章详情，Tag 目录仍要求登录。正文采用 Java 白名单渲染，摘要自动生成。
>
> 本文依据[最新讨论](https://chatgpt.com/share/6ae60ec2-6cf0-83ee-93c0-dba0bf64b296)与原始交接资料修订，取代V1 的异步 CRUD 历史计划。对话中途的 MQ、操作状态机和编辑版本建议已被后续决定替换，不继续叠加。
>
> 原交接文档及 V1 仅作历史资料，不叠加执行其中已取消的异步方案。

## 1. 本期范围与最终取舍

### 1.1 范围

V2 实现同步文章创建、完整修改、隐藏式删除、详情读取、作者编辑读取及管理列表；包含必要的 Tag 元数据、媒体引用、安全 HTML、详情缓存及按文章 ID 的列表展示 DTO 缓存。两种缓存均直接保存 coverUrl。

继续沿用此前明确收敛的范围：**本期不实现信息流、Tag 排序、浏览量、点赞及 ES。** 交接文档的相关章节只作为未来衔接背景，不据此恢复旧版排序、统计表、快照、队列或定时任务需求。

| V1 计划 | V2 决定 |
|---|---|
| API 发送命令，202 表示受理 | HTTP 请求同步完成 SQL；新增／修改共用 POST，成功均为 200 并返回 articleId；删除 204 |
| SQL Worker → MQ → Cache Worker | 同一请求内“准备 → 短事务提交 → 尝试缓存维护” |
| 后端提前分配文章 ID | MySQL AUTO_INCREMENT，INSERT 后取 generated key |
| expectedUpdatedAt 防编辑覆盖 | **取消**；接受最后成功提交的完整修改覆盖前一次 |
| updated_at 严格递增、充当令牌 | 仅展示更新时间，不作 CAS／版本冲突条件 |
| 单活消费、Confirm、ACK、重投与失败队列 | 全部移除，不添加任何 MQ 依赖、配置和组件 |
| 账户共享行锁与任务执行权限复核 | 复用现有请求鉴权；文章权限在文章事务内确认，不额外扩展用户锁协议 |
| 回源与缓存失效共用缓存锁 | 详情／列表 DTO 分离，两者回源与双缓存失效共用一把文章缓存锁；不跨 SQL 事务 |
| WATCH + MULTI/EXEC 条件操作 | 按新交接约定采用少量文章专用 Lua，原子检查 token 后操作缓存 |
| 任务补发保证缓存维护 | 有限重试、固定缓存 TTL、恢复维护；不再声称有持久化任务补偿 |
| 数据库错误处理 | 本期不自动查错、不自动查询提交结果、不自动重试 CRUD SQL；终止请求并准确返回错误 |

不引入文章 version、expectedVersion、expectedUpdatedAt、operation_id、pending_json、发布中状态、修订／任务表、Outbox、Redis 文章业务锁、Redisson 或通用任务编排框架。

### 1.2 并发语义

- 同篇文章的完整修改由 InnoDB 文章行锁串行化；多个有权请求可以先后成功。
- “最后一次”指**数据库中最后成功提交的完整修改**，不是最后点击、最后开始渲染、最后收到 HTTP 响应或最后清缓存的请求。
- 允许旧编辑页后来保存并覆盖新正文。这是明确接受的产品语义，不再返回编辑版本冲突。
- 统一 POST 根据请求体的 articleId 区分新增／修改；修改为完整内容替换，不自动合并两个请求的字段。
- 隐藏是终态：修改先提交则随后隐藏；隐藏先提交则迟到修改失败。普通修改永远不能重新公开文章。
- 隐藏不解除媒体引用，不物理删除图片；不提供恢复；彻底销毁另立需求。

### 1.3 权威来源和响应承诺

MySQL 决定文章、Tag、媒体引用、文件当前状态及资源归属。Redis 只是可重建缓存。

HTTP 写入成功表示 SQL 已确认提交，不表示所有读缓存已经同步，也不保证文章在响应发出后没有被另一个请求再次修改／隐藏。数据库与 Redis 不构成跨系统事务。

## 2. 交接假设与仓库现状的对齐

| 交接中的表述／示例 | 当前仓库事实与 V2 处理 |
|---|---|
| media_file 包含 object_key、public_url、unreferenced_at 等 | 现有表只有八字段；继续通过配置和 mediaId 推导公开 Key／URL，以 next_action_at 表示维护时点，不新增这些示例字段 |
| 两种展示缓存的封面 | detail／list DTO 均保存 coverUrl，命中直接返回；MediaService.publicUrl 是纯定位函数，不查询媒体表或对象存储，不新增 SQL cover_url 字段 |
| 引用类型示例 ARTICLE_CONTENT | 实际统一 ARTICLE_IMAGE，正文和封面合并去重 |
| 为媒体引用增加唯一键 | 已有主键 (ref_type, ref_id, file_id)，与要求等价；不重复加约束 |
| 更新只最终校验新绑定文件 | 现有 syncArticle 会校验整个目标集合；V2 直接复用，不为减一次检查而放宽既有 READY、归属和大小门禁 |
| Java／MyBatis 获取自增 ID | 持久层统一使用 MyBatis-Plus；普通 CRUD 使用 BaseMapper，复杂 SQL 放 XML。自增主键配置 IdType.AUTO，XML INSERT 使用 useGeneratedKeys；复用现有事务管理器 |
| 可用 COS／OSS 适配层 | 当前已实现生产 COS、开发 MinIO；本期不新增 OSS |
| 缓存 Lua 原子校验 | 认证已使用固定 Lua；文章复用现有 Spring Data Redis 加载方式，不引入通用脚本框架 |
| 信息流、计数随后衔接 | 本期做 CRUD、详情／列表 DTO 缓存；不缓存整页排序，不提前建 Feed、计数及搜索组件 |

实际约束来源：[媒体表](../../../../../backend/src/main/resources/db/migration/V2__create_media.sql)、[媒体引用服务](../../../../../backend/src/main/java/com/febrie/backend/media/application/reference/MediaReferenceService.java)、[文件回收服务](../../../../../backend/src/main/java/com/febrie/backend/media/application/lifecycle/MediaLifecycleService.java)、[用户 Repository](../../../../../backend/src/main/java/com/febrie/backend/user/infrastructure/persistence/AccountRepository.java)。

发现现有数据处于“有引用文件却非 READY”等异常状态时，本次保存失败并记录错误，不静默删除旧引用来掩盖问题。

## 3. 总体流程与事务边界

~~~mermaid
flowchart TD
    API["文章 HTTP 请求：使用已验证身份"] --> PRE["事务外准备：规范化 JSON、提取媒体、按可信 ID 生成地址和安全 HTML"]
    PRE --> FACADE["无外层事务的应用服务"]
    FACADE --> TX["独立 Spring Bean：RC 短事务"]
    TX --> CHECK["锁文章／新建行，确认存在、权限和未隐藏"]
    CHECK --> MEDIA["按文件 ID 顺序锁媒体，最终校验并维护引用"]
    MEDIA --> SAVE["文章、JSON、HTML、封面、Tag 与引用一次提交"]
    SAVE --> COMMIT{"SQL 提交结果？"}
    COMMIT -- "已确认回滚" --> ERROR["返回对应业务／数据库错误，不自动重试 SQL"]
    COMMIT -- "提交结果不明确" --> UNKNOWN["返回结果未确认，提示用户核对；不自动查询或重放"]
    COMMIT -- "已确认成功" --> CACHE["事务外：有限预算获取同一缓存锁，同时维护详情与列表缓存／隐藏标记"]
    CACHE --> C{"缓存维护成功？"}
    C -- 是 --> OK["保存返回 200 + articleId；删除返回 204"]
    C -- 否 --> LOG["保留 SQL 成功；记录告警、有限重试，固定 TTL 兜底"]
    LOG --> OK
~~~

删除不走渲染和媒体集合替换，只执行自身短事务，再进入相同的提交后缓存阶段。

### 3.1 准备阶段

- 读取服务端已认证 userId／role，不信任正文中的 authorId、role、HTML、状态或时间。
- 限制请求字节、节点深度、节点总量、图片数及 Tag 数，规范化 JSON。
- 从规范化 JSON 提取并去重媒体 ID，使用现有 MediaService.publicUrl 按固定规则生成公开地址，不为 URL 额外查询媒体表。
- 根据可信 ID 和当前存储配置生成规范图片地址，不信任任意 src、CSS、HTML 或事件属性。
- 本地生成安全 HTML，得到不可变 PreparedArticle；JSON、HTML、摘要和图片集合必须来自同一份规范化内容。
- 此处文件查询不提供最终绑定许可。渲染期间文件被 GC 标为 DELETING，最终事务仍须失败。
- READY 图片的定位规则不可变；已有文件后不热切换存储厂商、前缀或定位配置。准备阶段不下载、复制或审核远端图片。

渲染器采用受限 Tiptap 节点的 Java 白名单实现，不增加 Node 服务。正文 schemaVersion=1，节点、结构、属性与请求上限见最终契约，摘要从同一规范化正文自动生成。

### 3.2 短事务

核心写入使用显式 READ_COMMITTED，同一主库、同一 Spring 事务管理器。

- 创建：INSERT 新文章获得 ID 和新行锁，再锁媒体及建立关联。
- 修改：先按主键锁文章，锁内检查存在、作者和 hidden，再读取当前旧引用、锁新旧文件、替换完整内容。
- 删除：先锁同一文章行，锁内判断删除权限与当前隐藏状态，仅设置 hidden。
- 统一资源顺序为“文章行 → 新旧文件 ID 的稳定顺序”。不在文章事务中追加 Redis 锁、用户行锁、对象存储调用或 MQ。
- 文章行锁持续到事务提交或回滚，文件行锁也随事务结束释放；不依赖“文件锁先释放、文章锁最后释放”的具体物理顺序。
- 图片用途、归属、READY 和可信大小在文件锁内检查；文章关联与引用变更必须同事务。
- Tag 仅新增／读取目录，关系去重并验证存在；本期不引入 Tag 删除状态及其额外锁协议。

RC 本身不能代替锁；保护竞争资源的是锁定读、条件写及共同协议。[MySQL 锁定读说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)

### 3.3 Spring 事务代理必须真实生效

建议拆分为：

- ArticleService：无外层事务，组织准备、调用一次提交服务、隔离提交后的缓存维护异常；不组织数据库重试或自动查错。
- ArticleCommitService：公开的 create／update／hide 事务方法，独立 Spring Bean。
- ArticleContentRenderer：纯准备逻辑。
- ArticleQueryService、ArticleListHydrator：详情查询与列表 DTO 批量装配；ArticleCache 协调共享锁和双缓存维护，Detail／List CacheStore 分别管理两类 DTO。

调用必须经过 Spring 事务代理。提交服务方法在代理调用**成功返回后**，外层才能认为 COMMIT 已确认；不能在目标方法返回前就发成功响应，或从同一个类通过 this 调用绕过代理。[Spring 事务代理说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

媒体服务继续使用现有 MANDATORY 事务契约，不另开 REQUIRES_NEW。任何正文、Tag、引用失败都向外抛出并回滚，不捕获后继续提交。

## 4. 数据与 HTTP 契约

### 4.1 最小数据

| 表 | 内容 |
|---|---|
| article | id BIGINT AUTO_INCREMENT、author_id、title、summary、content_json、content_html、cover_media_id、hidden、created_at、updated_at、published_at |
| tag | 管理员维护的最小目录 |
| article_tag | 唯一关系 (article_id, tag_id)，必要的反向索引 |
| media_file／media_reference | 原样复用既有模型及约束 |

author_id 创建后不可修改。created_at／published_at 为首次创建事务内同一时间，编辑不刷新发布时间。updated_at 仅用于展示／管理查询，不要求它充当严格递增令牌，不出现带旧时间戳的拒写条件。

不新增浏览量／点赞量字段占位；后续统计不能借本次保存覆盖内容或隐藏状态。新增迁移不重写已存在的用户和媒体历史。

### 4.2 接口契约

| 方法与路径 | 本版语义 |
|---|---|
| POST /api/articles | 统一同步保存：articleId 不传／为 null 时新增，传入有效 ID 时修改；成功均为 200，返回字符串 articleId |
| DELETE /api/articles/{id} | 同步隐藏；204；授权成立的重复删除仍 204 |
| GET /api/articles/{id} | 普通正式详情，隐藏／不存在为 404；不记录浏览 |
| GET /api/articles/{id}/edit | 作者编辑读取，直接主库，JSON／封面／Tag 同一视图；隐藏返回状态冲突 |
| GET /api/users/me/articles | 主库只选择本人公开文章的页内 ID／分页，复用列表 DTO 缓存；不展示隐藏文章，不是信息流 |
| GET /api/tags | 录入用目录，不返回排序文章 |
| POST /api/admin/tags | 管理员新增 Tag 的最小支撑 |

统一保存请求包含可选 articleId、title、content、coverMediaId、tagIds，不接收独立摘要或 HTML。可空封面用明确 null 清除、Tag 用目标数组完整替换；不引入 PATCH 的自动字段合并。

统一 POST 的分支契约：

- articleId 未提供或为 null：新增。后端不使用客户端指定主键，事务内 INSERT 后取得 MySQL 自增 ID。
- articleId 为有效的正 BIGINT 字符串：修改。事务内锁定该文章，检查存在、作者权限及未隐藏，再保存完整内容。
- articleId 为 0、负数、空字符串或格式非法：400，不将无效 ID 当作新增信号。
- 传入 ID 但目标不存在：404，不自动创建、不进行数据库 UPSERT；无权为 403，已隐藏不能修改。
- 两个分支返回同一响应结构：新增返回本次自增 ID，修改返回原文章 ID。无变化但合法的修改仍可成功返回原 ID，不要求 SQL 必须改变一行内容。

成功响应示例：

~~~json
{"articleId":"123"}
~~~

Controller 只保留一个保存入口；ArticleService 在准备前根据规范化后的 articleId 明确分支，复用内容准备，分别调用既有 create／update 事务方法。合并 HTTP 入口不合并两种 SQL 的权限和锁内校验规则。

写接口沿用 Session + CSRF。普通作者只能改自己的文章；按新交接设计，作者本人、ADMIN、PATROLLER 可以隐藏文章；ADMIN/PATROLLER 的隐藏权不自动扩展为代写正文。身份和角色来自已经验证的服务端会话，事务内与锁定文章的 author_id 一起完成资源级判断。

沿用当前认证语义：已通过鉴权的请求允许完成，账号／角色变更后的后续鉴权拒绝旧会话。本版不额外承诺立即撤销在途请求，也不为了文章 CRUD 增加用户共享锁。仅单篇文章详情 GET 向游客开放；编辑读取、本人管理及 Tag 目录继续要求登录。

### 4.3 错误与重复请求

错误沿用 application/problem+json。格式／内容非法为 400，权限不足为 403，目标不存在为 404，隐藏后修改或文件暂不可绑定为业务冲突；不再有“文章编辑版本冲突”或发布任务错误。

新增错误码 ARTICLE_WRITE_RESULT_UNKNOWN，用于无法确认 COMMIT 结果的依赖异常；与明确未提交的 DEPENDENCY_UNAVAILABLE 区分，不自动重发 CREATE。

普通 UPDATE 影响行数为 0 不必然失败：相同值、驱动影响行数配置及时间精度都可能影响计数。锁内已确认合法文章且整体事务成功时，无实际内容变化可以作为成功；不能重新引入 0 行即 CAS 冲突的判定。[MySQL UPDATE 说明](https://dev.mysql.com/doc/refman/8.4/en/update.html)

重复 HTTP 创建不保证只产生一篇文章；HTTP 响应丢失／提交结果未知不能靠自动重试解决。请求级幂等键暂不引入，若产品不可接受重复创建，再独立设计。

## 5. 创建：自增 ID 与原子绑定

~~~mermaid
flowchart TD
    A["POST：articleId 不传／为 null，已认证身份与内容校验"] --> P["事务外规范化 JSON、按媒体 ID 生成地址、渲染 HTML"]
    P --> V{"准备有效？"}
    V -- 否 --> BAD["400／业务错误，无文章写入"]
    V -- 是 --> T["进入独立 RC 事务"]
    T --> I["INSERT article，读取数据库 generated key"]
    I --> F["按 ID 顺序锁媒体，检查归属、READY、用途、可信大小"]
    F --> R["同步正文与封面去重引用，保存 Tag 和完整内容"]
    R --> C{"COMMIT 结果？"}
    C -- "确认回滚" --> RB["整笔回滚，返回错误；不自动重试"]
    C -- "未知" --> U["返回结果未确认；不自动查询或重试 CREATE"]
    C -- "确认成功" --> K["事务外获取同一缓存锁，同时失效详情／列表的正负缓存"]
    K --> S["缓存失败单独处理；返回 200 + 新 articleId"]
~~~

INSERT 后在同一事务取得生成的主键，用该 ID 调用媒体引用服务并写 article_tag；不需要事务外预建草稿或 ID 服务。

沿用仓库统一的 MyBatis-Plus：实体配置 IdType.AUTO，BaseMapper.insert 后读取回填的实体 ID；自定义 XML INSERT 配置 useGeneratedKeys=true、keyProperty=id。insert 返回影响行数，不能将其当作生成 ID；文章与媒体引用继续共用现有事务管理器。[MyBatis generated keys](https://mybatis.org/mybatis-3/zh_CN/sqlmap-xml.html)

禁止 SELECT MAX(id)，禁止跨连接猜测主键。事务未提交时其他普通读事务看不到半成品。后续校验失败则文章和关联一起回滚，自增 ID 跳号正常。

本次创建失败即结束请求，不在服务端自动重新 INSERT。已回滚尝试消耗的自增 ID 不作为成功结果返回；若用户主动发起新的创建请求，由 MySQL 重新生成 ID。提交结果未知时先提示用户核对，不能自动重发创建。

## 6. 修改与隐藏：数据库自然串行

### 6.1 完整修改

~~~mermaid
flowchart TD
    A["POST：传入 articleId，身份和基础参数检查"] --> P["事务外准备规范 JSON 和 HTML"]
    P --> T["RC 事务：SELECT article FOR UPDATE"]
    T --> Q{"存在、作者有权、未隐藏？"}
    Q -- 否 --> E["404／403／隐藏冲突，整体回滚"]
    Q -- 是 --> O["读取锁内当前旧引用，确定新旧媒体并排序加锁"]
    O --> V["复核目标文件的 READY、归属、用途及大小"]
    V --> W["替换正文、封面、Tag 和引用集合；更新时间"]
    W --> C{"COMMIT 结果？"}
    C -- "确认回滚" --> ERROR["返回错误，不自动重试本次事务"]
    C -- "未知" --> UNKNOWN["返回结果未确认，提示用户核对；不自动查询或重放"]
    C -- "成功" --> CACHE["事务外持同一缓存锁失效详情／列表；不清隐藏标记"]
    CACHE --> OK["返回 200 + 原 articleId；缓存异常不撤销 SQL 成功"]
~~~

旧引用、旧封面和旧 Tag 必须在取得文章锁后读取，不能拿事务外的旧集合执行删除差集。PreparedArticle 只保存本次目标内容，不保存可变化的“旧引用快照”。

复用 MediaReferenceService.syncArticle：正文与封面合并去重；保留相同引用，新增差集，移除不再使用的引用；最后一处引用解除才重新安排无引用宽限期。

两个请求都可成功，后获得行锁并最终提交的完整请求覆盖前一个，包括封面、Tag 和引用集合。即使请求来自旧页面也不拒绝，这一点必须在产品交互中接受，不静默加入时间戳比较。

### 6.2 隐藏删除

~~~mermaid
flowchart TD
    A["DELETE：使用已验证操作人"] --> T["短事务：按主键锁同一文章行"]
    T --> Q{"目标存在且有隐藏权限？"}
    Q -- 否 --> E["404／403"]
    Q -- 是 --> H{"已经隐藏？"}
    H -- 是 --> SAME["不再改时间或引用，保持幂等"]
    H -- 否 --> U["仅 hidden=true 与更新时间；不触碰媒体引用"]
    SAME --> C["COMMIT"]
    U --> C
    C --> RESULT{"提交结果？"}
    RESULT -- "确认回滚" --> ERROR["返回错误，不自动重试 SQL"]
    RESULT -- "未知" --> UNKNOWN["返回结果未确认；不自动查询或重放"]
    RESULT -- "成功" --> K["SQL 成功后获取同一缓存锁，原子写墓碑及详情／列表负缓存"]
    K --> R["失败只记录／有限重试，仍返回 204"]
~~~

首期建议统一“锁文章 → 检查权限／状态 → 更新”，使错误分类清晰。后续可等价优化为权限受控的条件 UPDATE，但不同时维护两套删除实现；0 行必须辨别不存在、无权、已隐藏，不能一概返回失败。

重复已隐藏删除仍尝试修复缓存，便于恢复上一次提交后缓存失败；不能因为本次没有修改 SQL 就跳过后续维护。权限检查不能因为已隐藏便省略。

## 7. 文件绑定、GC 与锁范围

### 7.1 必须复用的媒体契约

- 调用者显式 RC，先锁业务文章，再由媒体服务按 ID 稳定顺序锁文件。
- owner 参数传文章作者，不用管理员／巡查员操作人替代。
- 整个目标集合满足 READY、uploader_id 归属、ARTICLE_IMAGE、可信 size_bytes；封面额外不超过 5 MiB，普通文章图片不超过 10 MiB。
- media_reference 已有复合主键，同一图片出现多次只有一条引用。
- 正文和引用使用同一事务管理器；不额外增加引用计数或自行绕过媒体服务插行。
- 移除引用只安排现有 next_action_at 宽限期，不立即删对象。
- 软删除文章保留全部引用，图片公开 URL 不因隐藏自动失效。

现有 READY 无引用宽限期为 24 小时。请求准备过久，文件可能在最终绑定前进入 DELETING；事务安全失败即可，不创建临时占用状态或“恢复图片”分支。

### 7.2 与 GC 的两种合法顺序

~~~mermaid
sequenceDiagram
    participant A as 文章绑定事务
    participant F as media_file 行锁
    participant G as GC 短事务
    alt 绑定先获得文件锁
        A->>F: 锁文件，确认 READY 与归属
        A->>F: 建立引用并提交
        G->>F: 获锁后重查引用和当前 next_action_at
        F-->>G: 有引用，不认领删除
    else GC 先获得文件锁
        G->>F: 锁内确认到期、无引用
        G->>F: 提交 READY → DELETING
        A->>F: 随后获锁，发现 DELETING
        F-->>A: 拒绝绑定，文章事务回滚
    end
~~~

GC 无锁扫描只是候选，不是删除许可。获得文件锁后还要重查**当前**到期时间与引用：扫描后绑定再解绑可能形成新的宽限期，不能沿用旧扫描时间提前删除。

现有 ISSUED 回收、DELETING 重试、事务外对象删除继续保留，不把 GC 改成只处理 READY。所有头像／未来绑定入口都遵守同一媒体行锁门禁；不得有绕过数据库直接清理公开对象的旁路。

同篇文章的修改先在文章行上排队，再竞争文件；不同文章仍可能共享文件或与 GC 竞争资源。稳定锁顺序、合适索引及短事务降低死锁风险，但本稿不承诺零死锁。发生死锁或锁等待异常时，本次文章事务终止，确保整笔回滚后返回数据库错误，**不自动重试**，也不在半失败事务内补 SQL 继续提交。现有文件 GC 对对象删除失败的后续维护与本条 CRUD SQL 策略分开，不因本次修订取消。[MySQL 死锁处理](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html)

## 8. Redis：详情／列表展示分离，共用缓存操作锁

### 8.1 两种 DTO 都能直接展示封面

| 类型 | 缓存内容 | 不承担的职责 |
|---|---|---|
| ArticleDetailView | articleId、title、authorId、authorName、summary、coverMediaId、**coverUrl**、tags、publishedAt、updatedAt、contentHtml | 不作为编辑提交数据的权威来源，不包含会话私有权限或管理信息 |
| ArticleListView | articleId、title、authorId、**authorName**、**summary**、**coverUrl**、**tags**、publishedAt 等列表必要字段 | 不加载／缓存整篇 HTML 或正文 JSON，不保存整页排序结果 |

tags 至少包含字符串 tagId 和展示名称；authorId／articleId 继续使用字符串。两种 DTO 的封面字段统一命名 coverUrl；没有封面时为 null，不能靠前端用 ID 拼 URL。

详情和列表分别按文章 ID 缓存，是“同一文章的两个展示投影”，不是把整个列表页存成一个 Key。缓存命中后，封面 URL、作者名、Tag 名称已经齐全，文章展示装配不再访问媒体／用户／Tag 查询来补这些字段。现有鉴权本身的数据库／Redis 校验仍照常执行。

封面 URL 的生成与可信边界：

- 文章 SQL 继续只保存 cover_media_id，不新增持久化 cover_url；封面是否 READY、所属作者和可信大小仍在保存事务内由媒体服务确认。
- DTO 首次装配或缓存失效后的回源时，使用既有 MediaService.publicUrl(coverMediaId)，根据固定配置与 ID 生成长期公开 URL。该方法当前不访问媒体表或对象存储。
- 生成的 coverUrl 与 DTO 一起序列化到 detail／list；后续缓存命中直接返回 URL，不能再凭 coverMediaId 查询文件或读取 URL。
- 不存上传 URL、一次性 Policy／签名，也不信任前端提交的任意封面 URL。改封面只接受 mediaId，两个展示投影都失效后重建。
- authorName 当前取不可变 username。首次装配通过 user.application 提供的最小、可批量显示资料读取接口取得，不复用含密码哈希的 AccountSnapshot，不让文章直接调用用户基础设施。
- 作者名与 Tag 名称也保存进两个 DTO。将来若增加可变昵称或 Tag 改名，先接受 TTL 内展示陈旧，再按实际需求补失效联动，本期不建立跨用户的缓存反向索引。

编辑接口继续直读主库取得 JSON、封面 ID、Tag 及当前内容；不能将共享详情缓存当作编辑权威数据。canEdit 等与当前访问者有关的标志只能在本次响应中计算，不写入共享 DTO。

### 8.2 Key、缓存三态与寿命

统一加环境和模块前缀。示例中的 {id} 作为同文章 hash tag，使同篇文章的条件脚本涉及的 Key 位于同槽。

| Key | 内容与建议初值 |
|---|---|
| ec:dev:article:v2:{id}:detail | DATA + ArticleDetailView，或短 MISSING |
| ec:dev:article:v2:{id}:list | DATA + ArticleListView，或短 MISSING |
| ec:dev:article:v2:{id}:cache-lock | SET NX PX，随机唯一 token，有限租期；两种缓存和提交后维护共用 |
| ec:dev:article:v2:{id}:hidden | SQL 确认隐藏后的墓碑；本期不自动过期，不由普通更新清理 |

对每种缓存分别表达三态：

- HIT：当前投影的正常 DTO，直接用于展示。
- NEGATIVE：当前投影明确缓存为 MISSING，普通公开查询按不存在处理。
- MISS：Key 不存在／已过期，需要协调回源；不能与 NEGATIVE 混用。

可参考 easy_blog_full 的 CacheValue<T>、ArticleDetailCacheStore、ArticleListCacheStore。Redis 中每种投影仍只用一个 Key 表示 DATA／MISSING，不额外堆叠一个共享 negative Key；隐藏墓碑负责跨两种投影阻断展示。

detail、list 正缓存各随机取 270～300 秒；MISSING 为 20 秒。命中不续 TTL，不使用滑动过期。旧项目详情 30 天／列表 24 小时的寿命不直接照搬：本场景没有可靠失效消息，必须让两种缓存都有可接受的固定 TTL 兜底。

不存在只能写短 MISSING，不能写永久 hidden。自增 ID 也可能被提前猜测，创建后须同时失效 detail、list 中可能存在的 MISSING。

无 Redis 文章业务锁，无自动无限续租，无跨 SQL／Redis 长锁。两个投影共用一把缓存操作锁，不另引入详情锁＋列表锁的嵌套获取协议。

### 8.3 每种缓存操作都原子验证 token

采用短小 Lua，所有写动作先判断 GET(cache-lock)==本调用 token；失配返回未执行，不能在 Java 检查之后无条件 SET／DEL。

| 动作 | 同一脚本内行为 |
|---|---|
| PUBLISH_DETAIL | 核 token、确认无 hidden，再写 detail 的 DATA 及固定正 TTL |
| PUBLISH_LIST | 核 token、确认无 hidden，再写 list 的 DATA 及固定正 TTL |
| PUBLISH_MISSING | 只有主库确认文章不存在时，核 token 后将 detail、list 一起设为短 MISSING；隐藏使用 HIDE_BOTH，不把权限／列表条件不符当成不存在 |
| INVALIDATE_BOTH | 核 token，在一个脚本内删除 detail、list；**不删除 hidden** |
| HIDE_BOTH | 核 token，设置 hidden，并将 detail、list 一起替换为短 MISSING |
| UNLOCK | 核 token，仅删除仍属于自己的 lock |

两种正缓存可以各自按需回填，不要求查列表时顺带读 HTML。对文章不存在／隐藏的判断是共享的，失效和负缓存同步操作必须一起维护两个投影，避免只清详情、列表仍显示旧标题／封面。

所有脚本参数、TTL 和 Key 类型先校验，固定操作范围；脚本异常和“锁已丢失”均不当作缓存成功。脚本原子性不意味着 MySQL 与 Redis 共享 ACID。[Redis 脚本原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)

回填被拒绝不等于本次 SQL 读取失败：已经取得完整 DTO 时，可在最终隐藏检查通过后用于本次响应，但失去 token 的线程不能继续写共享缓存。作者／Tag 等装配本身失败则不能返回半个 DTO。

无需为脚本引入第二把锁或外部锁库。该 Lua 仅用于文章缓存；认证现有固定 Lua 机制保持不变。若一类缓存已确认 MISSING、另一类仍见 DATA，普通读不能绕过 hidden；获得锁后按主库状态修复，不能把互相矛盾的缓存当成新事实。

### 8.4 详情读取与两类缓存的共同回源规则

1. 先查 hidden，命中直接 404；再查当前需要的 detail 或 list，明确区分 HIT／NEGATIVE／MISS。普通缓存命中不加锁。
2. 未命中获取同一篇文章 cache-lock，获锁后再次查 hidden 和本次需要的缓存，避免重复回源。
3. 仍未命中才回源主库。SQL 一致读取视图在获锁后建立，不能携带获锁前的旧快照／DTO 回填。
4. 主库只判断文章本身的存在与公开状态；不能把“当前用户无权编辑”“不属于某个列表条件”“作者／Tag 加载异常”误当成文章不存在并发布全局 MISSING。
5. 文章、Tag、封面 ID 和作者显示资料在一致读取视图中装配。详情需要 HTML，列表查询不选择 HTML／正文 JSON；媒体 URL 通过纯定位函数生成，不查询文件 URL。
6. 当前投影通过 PUBLISH_DETAIL 或 PUBLISH_LIST 发布；确实不存在用 PUBLISH_MISSING。SQL／作者／Tag 查询失败不写负缓存。
7. SQL 确认隐藏时可补 HIDE_BOTH。普通返回前再次检查 hidden，再通过 UNLOCK 释放自己仍持有的锁。
8. 缓存 DTO 使用不可变值或响应副本，不因添加访问者权限等响应字段污染共享缓存对象。

**未获得缓存锁的公开读请求**只允许有限等待、重查缓存，超限返回可重试依赖错误；不无锁回源写缓存。Redis 不可用时读返回 503，不自动降级 SQL；恢复使用停流量、停止旧实例及切换文章缓存版本的维护窗口。

GET 响应建议 no-store，避免浏览器／代理缓存绕过服务器隐藏过滤。任何文章读取降级都不绕过现有 Session 鉴权的 Redis 依赖。

### 8.5 列表 DTO 批量加载与作者管理查询

作者管理查询固定按 author_id 和 hidden=false 选择页内 ID，ID 倒序，page 默认 1、size 默认 20 且最大 50；不缓存整页，不返回 hidden 或正文。不存在隐藏条目私有装配、回收站或恢复入口。

1. 对页内 ID 批量读取 hidden 与 list，区分 HIT／NEGATIVE／MISS。
2. HIT 直接采用完整 DTO；NEGATIVE／墓碑与主库选页冲突的项留待主库批量核对。
3. MISS 非阻塞尝试每篇文章共享锁，获锁后批量二次检查；只对仍缺失的集合建立新的 RR 读取视图，合并文章、Tag 与作者资料查询。
4. 逐文章核 token 发布投影或双负缓存／墓碑，过期 token 不得写；按输入 ID 顺序恢复结果。
5. 本轮锁全部释放后才等待竞争项，超过等待预算返回 503，不把未确认项当作删除。
6. 返回前复查墓碑，缓存冲突项批量核对主库：仍公开且归属正确则装配，确认隐藏／不存在才省略；不绕锁修复共享缓存。

并发隐藏可以造成短页，不补页；total 是本次选页视图的数量。要核对最新正文、标题、Tag 和封面，始终使用 edit 主库接口，不依赖列表展示缓存。

### 8.6 提交后同时维护两种缓存

成功退出 SQL 事务后，再争抢同一 cache-lock：

- CREATE：INVALIDATE_BOTH，清两种投影可能提前生成的 MISSING，不清无关 hidden。
- UPDATE：INVALIDATE_BOTH，封面、作者展示信息、概要、标题或 Tag 变化不能只清某一个 DTO；不主动把本请求旧 payload 写成 DATA，不删除 hidden。
- HIDE：HIDE_BOTH，建立共享墓碑并将两种投影一起转为短 MISSING。
- 当前请求内至多 3 次有限缓存维护，竞争等待累计不超过 300ms；单次 Redis 命令仍受现有 2 秒超时约束。不建立后台队列或重放旧 HTML／封面 URL。

更新 A 的缓存动作晚于 B，只会再次失效两个投影，不把 A 的旧 URL 写回。更新失效晚于删除，即使清掉两种 MISSING，hidden 仍在，两条公开读取路径都不能复活文章。

~~~mermaid
sequenceDiagram
    participant R as 详情／列表回源
    participant K as 共用文章缓存锁
    participant W as 提交后缓存维护
    R->>K: 获 token A，回源取得旧展示数据
    Note over W: 新正文／封面或隐藏已在 SQL 提交
    W->>K: 等待同一缓存锁
    alt A 尚有效
        R->>K: 核 A 后发布本投影，条件解锁
        W->>K: 获 B，同时失效 detail 与 list，或 HIDE_BOTH
    else A 已过期
        W->>K: 获 B，完成双缓存维护
        R->>K: 恢复后尝试用 A 回填／解锁
        K-->>R: token 不匹配，拒绝旧动作
    end
~~~

共用锁不要求 detail、list 在所有时刻含相同读取时间的数据：两者独立命中且各自有 TTL。它保证提交后的双缓存维护不会被失去写入资格的旧回源覆盖，不能据此承诺跨 SQL／Redis 强一致。

### 8.7 easy_blog_full 的参考范围

已对照旧实现的 ArticleDetailCacheStore、ArticleListCacheStore、CacheValue、ArticleListHydrator、ArticleChangedListener 及详情／列表 DTO：

| 可参考 | 本社区必须修订 |
|---|---|
| 详情与列表分离、按文章 ID 缓存 DTO | 两个 DTO 都带统一 coverUrl，补齐社区作者名和 Tag 名称 |
| 缓存三态、带抖动 TTL、获锁后二次检查 | 正 TTL 按本社区无可靠消息兜底的窗口设置，不照搬 30 天／24 小时 |
| 列表批量读缓存、缺失合并回源、恢复输入顺序 | 只查列表字段，批量作者／Tag；封面 URL 纯定位生成，无媒体 URL 回查 |
| 发布／失效／解锁时校验 token | 旧项目详情和列表分锁，本社区共用一把文章缓存操作锁，双失效／隐藏原子完成 |
| SQL 提交后维护派生缓存 | 当前无 MQ；不把 AFTER_COMMIT 当成可靠投递，仍接受固定 TTL 的崩溃兜底 |
| 回源失败与确认不存在分开 | 不照搬锁竞争后普通无锁回源，不把隐藏的作者管理记录写进公开缓存 |
| DTO 响应副本思路 | 不引入旧项目浏览量合并、排行榜版本或物理文件删除监听 |

参考代码用于复用思路，旧的 blog: Key、Integer ID、统计依赖和单用户假设均不能原样复制到社区。

## 9. 缓存失败、崩溃与结果不明确

### 9.1 SQL 已确认提交，缓存失败

提交后维护设置有限等待／调用预算；失败只记录阶段指标，可在预算内少量重试，随后保存仍返回 200 + articleId，删除仍返回 204。不向前端暴露内部“缓存是否同步”来决定保存是否成功。

缓存异常必须在外层应用服务隔离，不能漏到统一 DataAccessException 处理器变成 503，从而把已成功写入伪装为回滚；也不能重新调用 commitCreate／commitUpdate 来“修复缓存”。

可做有界的进程内按 articleId 重试，但它不是可靠消息，进程退出可能丢失。正确性不能依赖一份声称永不丢失的内存重试队列。

### 9.2 COMMIT 后进程立即退出

没有 MQ、Outbox 或任务记录，本期不保证每次缓存失效必然执行。进程在 COMMIT 后、双缓存失效前退出，旧详情或列表 DTO 分别存在到各自固定 TTL；提前猜中 ID 的两类负缓存也可能继续存在到短 TTL。

有界陈旧依赖以下前提：

- detail、list 各自的正缓存／负缓存 TTL 有最大值，命中不续期；不能只给详情设置上限、让列表长期陈旧。
- SQL 回源从主库读取，读取视图在缓存锁内建立。
- 缓存锁有限租期、不无限续租；过期线程不能继续写。
- 迟到更新只做失效，不用旧 payload 延长旧正文 TTL。

因此未来新请求的缓存陈旧窗口由剩余 TTL 及已在途持锁回填的有效租期等预算共同约束，不能说“必定在一次 DEL 后立即最新”。已发出的响应不在撤回保证内。

作者编辑接口始终直读 SQL，用于核对最新正文及元数据；管理列表的文章存在／hidden／页内归属来自主库，但公开展示 DTO 可有缓存陈旧，不能用缓存标题证明最新保存内容。若要求详情或列表展示立即最新、下架零延迟，需另加强权威状态检查；本版不以补回 MQ 来假装自动满足。

### 9.3 业务与数据库错误：终止请求，不自动查错或重试

本期不构建自动诊断、提交结果查询、事务恢复任务或 SQL 重试机制。即使错误可能通过重新执行恢复，也先结束本次请求并返回相应错误；只有未来实际需要提高可用性时，再独立评估数据库重试。

| 情况 | 处理 |
|---|---|
| 渲染／业务校验失败，如文件不存在、归属不符、文章已隐藏 | 准备阶段直接返回业务错误；事务内发现则整笔回滚后返回业务错误；不重试 |
| 死锁，数据库已回滚事务 | 清理本次事务并返回数据库错误；不自动重试，即使它通常可恢复 |
| 锁等待超时等异常 | 让异常穿过 Spring 事务边界，确保整笔回滚，再返回数据库错误；不自动重试 |
| COMMIT 连接中断、是否提交不明确 | 返回结果未确认，提示用户核对；不自动查询数据库来判定本次请求结果，也不自动重放 SQL |
| SQL 已确认成功，仅缓存维护失败 | 保留保存／删除成功结果；只允许缓存阶段在预算内有限重试，绝不重新执行 SQL |
| HTTP 响应丢失 | 客户端不能断言 SQL 未执行，不自动重发写入请求；由用户核对后决定后续操作 |

锁等待超时默认可能只回滚失败语句，不能据此认定整个事务已经回滚；事务内不能捕获数据库异常后继续提交，异常必须触发 Spring 整笔回滚。[InnoDB 错误处理](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html)

COMMIT 结果未确认属于应用与数据库的通信确认问题：可能数据库已经提交，只是成功回执丢失，也可能没有提交。因此不能把连接异常统一描述为“保存失败且已回滚”，但也无需为首期增加自动核对系统。保留必要日志，用户通过正常编辑／管理查询核对即可。

本节取消的是 CRUD SQL 自动重试及自动查错。第 9.1 节已有的缓存有限重试、固定 TTL 以及现有文件 GC 维护策略保持独立；缓存处理也不得反过来触发 SQL 重试。

### 9.4 Redis 恢复与墓碑管理

本期墓碑无自动 TTL，后续若要回收需单独设计依据；“无 TTL”不代表 Redis 不会丢失。

恢复时停止流量及旧实例，切换全新文章缓存命名空间使旧 DATA／MISSING 退出使用，从 SQL 重建隐藏标记，完成后再统一启动实例恢复流量。恢复命令拒绝已有 Key 的版本，只操作文章前缀，不清空共享 Redis。

普通公开更新不能擅自清墓碑；若出现 SQL 公开但有墓碑，作为异常受控修复。正常状态下自增 ID 不复用且隐藏不可恢复。

## 10. 主要竞态与验收规则

本版不再测试消息乱序、ACK 或队列重投，而是验证 SQL 串行、文件 GC 及缓存交错。

| 编号 | 危险交错 | V2 处理／验收断言 |
|---|---|---|
| C01 | 两个作者请求都基于旧正文提交 | 两笔可先后成功；最后提交的整份内容、Tag、封面和引用获胜，不返回版本冲突 |
| C02 | 后开始渲染的请求先提交 | 以实际 SQL 提交顺序为准，不以请求到达顺序判断新旧 |
| C03 | 请求在事务外看到文章公开，期间被巡查员隐藏 | 获文章锁后重查 hidden；修改失败且引用不变 |
| C04 | 更新先获文章锁，删除在等待 | 更新提交后删除成功；最终隐藏且文件引用保留 |
| C05 | 删除先提交，旧修改随后拿到锁 | 修改拒绝，不更新正文／Tag／引用，不清隐藏标记 |
| C06 | 多个删除并发 | 至多一次公开转隐藏；授权成立的重复删除仍 204，并可再次修复缓存 |
| C07 | 创建插入已得 ID，后面文件校验失败 | article、Tag、引用全部回滚；跳号正常，不返回该 ID 作为成功 |
| C08 | 双创建请求／响应丢失后再次 POST | 可能生成两篇，属于暂接受的 HTTP 幂等限制；不以同步提交或自增 ID 宣称去重 |
| C09 | 准备时已根据媒体 ID 生成地址，提交前 GC 已认领 | URL 可生成不代表允许绑定；锁内复核 DELETING，整笔拒绝绑定 |
| C10 | GC 扫描无引用，绑定先持锁并提交 | GC 获锁后看到引用，不删除 |
| C11 | GC 扫描旧到期时间，期间绑定再解绑产生新宽限期 | GC 锁后检查当前 next_action_at，不提前回收 |
| C12 | 两篇文章引用文件顺序相反／同图多次出现 | 文件按 ID 顺序锁定、集合去重、复合主键；死锁时整笔回滚并返回数据库错误，不自动重试 |
| C13 | 更新掉图片，旧正文缓存／在途响应仍引用 | 只安排既有无引用宽限期，不立即物理删图；引用关系与正文同事务 |
| C14 | 读取文章字段、作者名、Tag、封面 ID 之间发生更新 | 按投影选择字段，在一致读取视图中组装完整 DTO，coverUrl 纯定位生成，不能混合不同次提交的数据 |
| C15 | 详情或列表回源取得旧数据，SQL 提交后双缓存失效 | 共用缓存锁；读先完成则提交后同时失效；锁过期则旧读 token 不得写任一投影 |
| C16 | 读认为不存在，CREATE 提交双失效后旧线程再写 MISSING | 两类正负回填同样受 token 保护；创建后清两类负缓存，不能只清 detail |
| C17 | 锁 A 过期，锁 B 操作完成，A 恢复 | A 的详情／列表回填、双失效、HIDE_BOTH、解锁都必须核 token，不能误删 B 或覆盖缓存 |
| C18 | 先更新后隐藏，但更新的缓存清理最后执行 | INVALIDATE_BOTH 永不清 hidden；迟到清两种缓存不会恢复公开 |
| C19 | 更新 A、B 的缓存维护与 SQL 提交顺序相反 | 两者只失效，不回写自己的旧 HTML；下次回源读最新 SQL |
| C20 | SQL 已提交但缓存调用失败／进程退出 | 已知提交时仍返回成功；未执行维护靠固定 TTL 收敛，不能声称有可靠补发 |
| C21 | COMMIT 成功与否不明确、连接中断 | 返回结果未确认，不自动查询或重放 SQL；与明确回滚、缓存失败分别处理 |
| C22 | 无变化更新返回影响行数 0 | 锁内合法且事务成功可返回成功，不解释为版本冲突 |
| C23 | 缓存已命中正文，返回前文章被隐藏 | 再查墓碑可缩小窗口；检查后／已下发响应仍允许完成，不承诺撤回 |
| C24 | Redis 重启、旧备份恢复、Lua 成功响应丢失 | 恢复期主库校验、隔离旧写及失效旧缓存；缓存动作可重复，普通更新不清墓碑 |
| C25 | 改标题／概要／封面／Tag 后只失效详情，列表继续展示旧字段 | INVALIDATE_BOTH 在同一 token 条件脚本内清两类缓存；列表和详情回填均有 coverUrl |
| C26 | 文章存在，但作者／Tag 批量查询失败或返回不全 | 依赖／数据错误，不把文章写成 MISSING，不返回缺作者名或 Tag 的半个缓存 DTO |
| C27 | 列表批量查询期间较早取得的某些文章锁到期 | 逐文章核 token；过期项不写，未确认项不伪装成删除；不能靠批量查询成功跳过所有权校验 |
| C28 | 作者列表页内文章随后隐藏，或公开负缓存与分页结果冲突 | 主库仅选择本人公开文章；冲突批量核对，确认隐藏/不存在才省略，不补页，依赖失败不伪装成删除 |

其中 C01 是接受的覆盖语义；C08、C20、C21、C23 是明确保留的边界，不用“已解决全部竞态”掩盖取舍。

## 11. 测试与观测

### 11.1 必须实测

| 分组 | 方法与关键断言 |
|---|---|
| 事务代理与多组件原子性 | 真实主库中，在文章、Tag、媒体步骤注入失败，断言跨 Repository 全回滚；调用确实经过事务代理 |
| 自增主键 | 插入后 ID 可用于同事务引用；回滚不留文章／引用，跳号正常；失败不自动再次 INSERT |
| 死锁与锁等待 | 注入异常，验证本次事务无部分提交、返回数据库错误，不自动调用第二次提交，也不进入提交成功后的缓存维护 |
| 双写覆盖 | 用屏障控制两个事务的获得锁／提交顺序，两者成功，最终整份内容及引用匹配后提交者 |
| 双向删写 | 固定更新先、删除先两种时序，断言最终隐藏和迟到更新拒绝 |
| 删除权限与幂等 | 作者、ADMIN、PATROLLER、其他作者及并发重复删除；无权不能因已隐藏绕过校验 |
| 媒体 GC | 扫描后绑定、GC 先认领、绑定再解绑刷新宽限期，确认两条路径共同门禁 |
| 双投影正负旧回填 | 分别暂停详情／列表回源，完成更新／隐藏／创建后恢复；验证共享锁、双失效及 token |
| 锁过期 | A 失去租期、B 获锁，验证 A 各类脚本与 unlock 全部被拒绝 |
| 迟到维护 | 控制更新缓存失效晚于删除、A 失效晚于 B，墓碑保留，旧详情／列表／封面 URL 均不被重新写入 |
| 两类 DTO 的展示字段 | detail／list 缓存均包含 coverUrl；list 包含标题、作者名、概要和 Tag ID／名称；无封面为 null |
| 缓存命中无 URL 回查 | 预置完整缓存后，验证文章装配不调用媒体／对象存储 URL 查询，也不为作者名和 Tag 临时查库；不把现有鉴权 SQL 计为文章缓存回源 |
| 列表批量与公开边界 | 验证批量读缓存、缺失合并 SQL、批量作者／Tag、恢复原 ID 顺序、不读取 HTML；隐藏文章不入选，缓存冲突由主库确认 |
| 数据错误不负缓存 | 作者／Tag 加载失败、SQL 超时、批量项未确认不发布 MISSING，不静默丢行 |
| 统一 POST 分支 | 不传／null ID 新增并返回自增 ID；有效 ID 修改并返回原 ID；非法 ID、目标不存在、越权或已隐藏均不得回退到新增 |
| 提交后 Redis 失败 | 真实 SQL 成功，注入 Redis 超时／锁争用，确认保存仍返回 200 + articleId、删除返回 204，不再次执行 SQL |
| 崩溃窗口 | COMMIT 后、双缓存维护前退出，核对数据库已提交、detail／list 各自 TTL 到期后回源正确 |
| 提交未知 | 与死锁明确回滚区分；不自动查询本次结果或重放 CRUD SQL；日志及接口准确表达结果未确认，提示用户核对 |
| 查询一致性 | 同一投影装配文章、作者、Tag 期间插入更新，DTO 来自一致读取视图；编辑核对不依赖陈旧列表缓存 |
| 响应兼容 | 新增／修改统一 POST、200 与 articleId 响应，no-store、ID 字符串、无 expectedUpdatedAt、无 202／任务字段、无浏览点赞副作用 |

并发测试用 latch／barrier 或可控钩子暂停关键位置；不要用随机 sleep 证明锁正确。行锁、事务、GC、Lua、租期和进程退出必须在专用 MySQL／Redis 集成环境验证；Mock 只验证准备规则与异常分类，不能替代这些验收。

### 11.2 最小观测

记录请求关联 ID、articleId、操作、准备耗时、事务耗时／锁等待、已确认回滚／提交未知、detail／list 各自三态命中、批量回源数量、缓存锁等待／token 失效／双缓存维护失败。日志不保存正文、凭证或对象存储密钥。

区分 SQL 成功与缓存结果；告警关注缓存维护失败率、持续锁争用、死锁／超时、GC 失败和未知提交。没有消息队列，就不保留队列／重投／死信监控假接口。

## 12. 代码组织与分批计划

> 本节分批表保留原实施顺序，不是当前待办；代码组织和契约仍按当前实现维护。

保持当前单 Maven 工程和按业务分包，不为未来功能创建空模块：

| 位置／组件 | 职责 |
|---|---|
| article.web、request、response | 同步 CRUD、身份转换、HTTP 状态 |
| article.application.ArticleService | 准备、单次调用提交服务、提交后缓存维护隔离；无 SQL 重试或自动查错 |
| article.application.ArticleCommitService | 受 Spring 代理保护的 RC 写事务 |
| article.application.ArticleContentRenderer | 规范化 JSON 与安全 HTML |
| article.application.ArticleQueryService／ArticleListHydrator | 详情、编辑、作者管理查询及列表 DTO 批量装配；编辑主库直读，管理权限／状态由主库确定 |
| article.infrastructure.persistence | 文章／Tag SQL、自增 ID；沿用同一主数据源 |
| article.infrastructure.cache | CacheValue 三态、ArticleDetailCacheStore、ArticleListCacheStore、共用缓存操作锁、双失效／隐藏 Lua |
| user.application 的显示资料读取入口 | 仅返回文章展示所需 ID／作者名，支持批量；不复用密码／身份快照 |
| media.application | 复用现有引用与生命周期服务，不扩出第二套文件绑定 |

不新增 messaging 包、MQ 配置、Worker、任务状态 API、信息流／统计定时任务。现有文件 GC 继续运行。纯粹的缓存恢复若需要定时触发，再按实际职责加最小任务，不建通用调度框架。

| 批次 | 交付 | 重点验收 | 建议提交消息 |
|---|---|---|---|
| 1 | 新文章／Tag 最小模型、请求规范、规范化内容及安全渲染 | 格式、可信图片地址、权限字段不可伪造 | feat(article): 建立文章模型与安全内容契约 |
| 2 | 自增创建、完整修改、隐藏删除、Tag 与媒体原子提交 | C01～C13、C21、C22，事务代理、自增主键、异常回滚与不自动重试 | feat(article): 实现同步文章事务与媒体引用维护 |
| 3 | 统一 POST 保存、删除接口、编辑主库读取／管理查询、详情与列表 DTO；作者显示资料批量接口 | ID 分支与返回值、两类 coverUrl、角色范围、无乐观版本、无统计副作用 | feat(article): 接入同步文章增删改查接口 |
| 4 | 详情／列表独立三态缓存、列表批量加载、共用锁、双失效 Lua、提交后维护与失败隔离 | C14～C28，命中不查封面 URL、批量锁过期、公开管理边界、SQL 成功不被缓存失败覆盖 | feat(article): 实现文章双缓存与批量展示加载 |
| 5 | 专用环境并发／故障联调、缓存恢复手册和接入文档 | 双写、删写、GC、死锁／超时错误返回、提交未知不自动核对或重放、TTL 边界 | test(article): 验证同步文章竞态与故障恢复 |

每批按实际内容继续拆小，测试随功能加入；最后一批是完整联调，不将并发测试全拖到最后。所有新增代码保留适当中文注释，解释前提、锁内校验和状态语义，不把已保证的纯格式检查层层复制。

按仓库约定执行相关测试，最终 mvn clean verify。集成测试使用专用库、随机 Redis 前缀；不连接业务库或清空共享 Redis。具体通过与跳过项以接入文档中的验收记录为准，不将设计验收清单自动视为实测结果。

## 13. 最终取舍与演进边界

| 项目 | 最终选择／明确限制 |
|---|---|
| 本期范围 | CRUD、Tag 元数据、媒体引用、安全渲染、详情与列表 DTO 缓存；信息流／排序／浏览／点赞仍延期 |
| 持久化适配 | 统一采用 MyBatis-Plus：BaseMapper/LambdaWrapper 处理普通 CRUD，复杂 SQL 放 XML；自增 ID 回填实体，复用既有事务 |
| 渲染实现 | 固定 schemaVersion=1 与 Java 节点白名单，不增加 Node 服务或异步发布系统 |
| 访问策略 | 作者自改，作者／ADMIN／PATROLLER 可隐藏；游客只开放单篇详情 GET |
| 缓存参数 | detail／list 正 TTL 270～300 秒、负 TTL 20 秒；共享锁 5 秒不续租，等待预算 300ms，页大小最大 50 |
| 封面与列表字段 | 两类 DTO 均保存 coverUrl，命中不回查文件 URL；列表含作者名、概要、标题、Tag 展示字段，无封面为 null |
| 写后可见性 | 双缓存失效失败时详情／列表展示可陈旧；编辑核对直读 SQL，管理页归属／公开状态来自主库，隐藏文章不展示 |
| 写入错误处理 | 事务内业务错误和可确认回滚的数据库异常终止请求，整笔回滚后返回错误；提交未知按下一项处理，无 CRUD SQL 自动重试或自动查错 |
| 提交结果未确认 | 提示用户通过正常查询核对，不自动判定回滚、不自动查询结果或重发；重复 HTTP 创建幂等暂不保证 |
| 账户变更 | 复用“已鉴权在途请求可完成”，不新增用户锁或撤销已运行写入协议 |
| Lua 引入 | 仅文章缓存原子条件动作，不更改认证 Redis 设计 |
| 异常恢复 | 接受固定 TTL 兜底与维护窗口恢复，不承诺每次提交后维护持久化必达 |

将来接入 ES，不意味着必须引入 MQ；可先评估定时重建／增量校准。需要可靠低延迟变更传播时再独立设计 Outbox、CDC／MQ 及更新删除顺序。普通 updated_at 尚不是已证明可靠的变更序列，不能直接把它当成不会漏写的增量游标。

昂贵审核、长耗时渲染、请求级幂等、协同编辑、文章修订或零延迟下架均需新目标与独立设计；本版不提前加入相应状态和锁。

## 14. 审核依据

- [最新完整对话：同步短事务、最后写入覆盖、自增 ID 和缓存操作锁](https://chatgpt.com/share/6ae60ec2-6cf0-83ee-93c0-dba0bf64b296)。
- 用户提供的原始交接资料 `community_article_codex_handoff.md`，作为来源保留，不直接执行其本次范围外实施指令。
- V1 历史计划 `article-feed-v1-design.md`，与本版不累计执行。
- [Easy_blog_full 文章模块参考实现（本次检视提交 37d0489）](https://github.com/Lancasteerr/Easy_blog_full/tree/37d0489e024f2d0e8cbbedcf52973218d2340aa3/demo_bk/src/main/java/com/febrie/demo_bk/article)：参考双 DTO 缓存、CacheValue、批量加载及 token 条件操作，以第 8.7 节列出的社区修订为准。
- [现有文件服务](../../../guides/media/zh-CN/media-service.md)、[代码组织约定](../../../architecture/backend/zh-CN/backend-architecture.md)、[媒体引用服务](../../../../../backend/src/main/java/com/febrie/backend/media/application/reference/MediaReferenceService.java)、[文件生命周期服务](../../../../../backend/src/main/java/com/febrie/backend/media/application/lifecycle/MediaLifecycleService.java)。
- 官方参考：[MySQL 锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)、[死锁处理](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html)、[UPDATE 行数](https://dev.mysql.com/doc/refman/8.4/en/update.html)、[MyBatis 自增主键](https://mybatis.org/mybatis-3/zh_CN/sqlmap-xml.html)、[Spring 事务代理](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)、[Redis Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[Redis 锁 token 与租期](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)。
