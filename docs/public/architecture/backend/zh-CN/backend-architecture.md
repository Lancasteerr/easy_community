# 后端代码组织与架构约定

## 统一文章列表查询 v2

`article.search`集中维护标题/Tag/作者检索、本人列表、PIT游标、推荐算分和可靠索引同步。内容事务在创建/编辑/首次隐藏时写meta_version与Outbox；新增主库Mapper由统一sqlSessionTemplate扫描，XML位于mapper/main/article/search。消费者读取短RR快照并通过ES脚本防倒退，隐藏留墓碑，统计不修改元数据版本。

搜索/feed/本人独立入口共用ES候选与公开卡片装配，按ES顺序批量回查，过滤已知无效及作者不匹配项，不额外强查主库状态。本人作者来自Session，游标绑定入口和实际作者；公开作者筛选支持游客。响应阶段批量装配计数和个人点赞状态。排名只读SQL七日浏览量，日期候选与每日校准共用分数Bulk，不建立排名锁或批次状态表。

Relay与排名分别使用专用单线程调度器，默认taskScheduler归属保持明确。非Web维护负责导入/对账、有限重放及维护窗口别名切换，见[检索运维说明](../../../operations/search/zh-CN/article-search-operations.md)。

[English](../en-US/backend-architecture.md) · [启动、接口和部署说明](../../../../../backend/README.md)

## 从业务找到代码

后端保持单个 Maven 工程。先按用户、认证等业务找包，再按接口、业务流程和数据库操作找子包。这样一次用户需求涉及的代码通常集中在 `user`，而不是散落在全局 Controller、Service 和 Mapper 目录中。

包根为 `com.febrie.backend`：

```text
backend
├─ BackendApplication
├─ article
│  ├─ common
│  ├─ content
│  │  ├─ application
│  │  │  └─ dto
│  │  ├─ infrastructure
│  │  │  ├─ cache
│  │  │  ├─ config
│  │  │  ├─ maintenance
│  │  │  └─ persistence
│  │  │     ├─ entity
│  │  │     └─ mapper
│  │  └─ web
│  │     ├─ request
│  │     └─ response
│  ├─ like
│  │  ├─ application
│  │  │  └─ maintenance
│  │  ├─ domain
│  │  ├─ infrastructure
│  │  │  ├─ config
│  │  │  ├─ maintenance
│  │  │  ├─ messaging
│  │  │  ├─ observability
│  │  │  ├─ persistence
│  │  │  │  └─ mapper
│  │  │  └─ redis
│  │  └─ web
│  ├─ tag
│  │  ├─ application
│  │  │  └─ dto
│  │  ├─ infrastructure
│  │  │  └─ persistence
│  │  │     ├─ entity
│  │  │     └─ mapper
│  │  └─ web
│  │     └─ request
│  └─ view
│     ├─ application
│     └─ infrastructure
│        ├─ cache
│        ├─ persistence
│        │  ├─ entity
│        │  └─ mapper
│        └─ scheduling
├─ auth
│  ├─ application
│  │  └─ dto
│  ├─ infrastructure
│  │  ├─ config
│  │  ├─ ratelimit
│  │  ├─ scheduling
│  │  ├─ security
│  │  └─ session
│  └─ web
│     ├─ request
│     ├─ response
│     ├─ security
│     └─ session
├─ media
│  ├─ application
│  │  ├─ lifecycle
│  │  ├─ reference
│  │  └─ upload
│  │     └─ dto
│  ├─ domain
│  ├─ infrastructure
│  │  ├─ config
│  │  ├─ persistence
│  │  │  ├─ entity
│  │  │  ├─ mapper
│  │  │  └─ notification
│  │  ├─ ratelimit
│  │  ├─ scheduling
│  │  └─ storage
│  └─ web
│     ├─ notification
│     └─ request
├─ shared
│  ├─ error
│  ├─ management
│  ├─ persistence
│  ├─ ratelimit
│  └─ web
└─ user
   ├─ application
   │  └─ dto
   ├─ domain
   ├─ infrastructure
   │  ├─ bootstrap
   │  └─ persistence
   │     ├─ entity
   │     └─ mapper
   └─ web
      └─ request
```

这是一种实用分层，不要求每个业务都有四层。当前认证没有独立的纯业务模型，因此不建 `auth.domain`；未来新增帖子、评论时，同样按实际代码建包。

## 包名怎么理解

| 包名 | 放什么 | 当前例子 |
|---|---|---|
| `web` | 接收 HTTP 请求、读取已认证身份、返回结果 | `UserController`、`AuthController` |
| `application` | 组织一次操作的步骤和事务，提供其他业务可调用的服务 | 创建用户、改密、登录流程、账户查询 |
| `domain` | 不依赖数据库、HTTP 和框架的业务类型或规则 | 公开的 `Role` 枚举 |
| `infrastructure` | 数据库、Redis、安全配置等具体实现 | 账户 SQL、限流计数、会话集合 |
| `persistence` | 从数据库读取和保存数据 | `AccountRepository` 封装 MyBatis-Plus Mapper |
| `dto` | 服务与调用方之间传递的数据；DTO 指数据传输对象 | `UserActor`、`UserView`、`AccountAuthState` |
| `request` / `response` | 某类 HTTP 接口的请求、响应对象 | `LoginRequest`、`CsrfView` |
| `scheduling` | 安排定时任务何时执行 | `SessionCleanupTask` |
| `shared` | 多个业务共同使用、又不属于某个业务的基础能力 | `ApiException`、统一错误响应 |

同名单词还需结合父包理解：`auth.web.session` 操作 HTTP 会话和 Cookie，`auth.infrastructure.session` 操作 Redis 中的有效会话集合；`auth.web.security` 在请求进入接口前校验身份，`auth.infrastructure.security` 接入密码认证提供器。

## 现有流程如何协作

### 登录和请求校验

1. `SecurityRequestFilter` 在 CSRF 检查前限制登录入口的请求频率，只读取容器处理后的客户端 IP。
2. `AuthController` 接收参数，将用户名、原始密码和 IP 交给 `AuthService`。
3. `AuthService` 去除用户名首尾空白并保留大小写、检查失败次数，再调用 Spring Security 的认证管理器。仅认证失败增加失败计数，数据库或 Redis 故障继续向外传播。
4. `LocalAuthenticationProvider` 通过 `AccountQueryService` 调用用户模块；用户模块使用一次 JOIN 读取密码哈希、角色、状态和安全版本。账号不存在时仍执行一次密码哈希匹配。
5. 验证成功后，`SessionService` 轮换 HTTP 会话，立即保存认证快照，再登记 Redis 集合成员。Controller 返回不含凭证的用户信息。
6. 后续受保护请求由 `SessionValidation` 检查绝对到期时间、主库账户状态、安全版本及集合成员资格，不重新加载角色，也不自动升级会话版本。

用户名由 `AccountPolicy` 统一校验为 `[A-Za-z0-9_]{4,32}`，创建、管理员初始化和登录均保留大小写。`Alice_1` 与 `alice_1` 是独立账号，查询和唯一约束由用户名及本地身份标识字段现有的 `utf8mb4_bin` 排序规则保证大小写区分。账号失败计数使用去首尾空白后保留大小写的用户名，IP 限流仍共享。此次直接更新 V1 建表注释，按新建数据库使用，不提供旧数据或已有 Flyway 记录的兼容处理。

`AccountSnapshot` 含密码哈希，只能用于内部认证，不能作为 HTTP 响应或写入 Session。`SessionPrincipal` 保存登录时确定的身份和到期时间，不包含密码。HTTP 用户信息使用 `UserView`，用户 ID 保持字符串，用户名保留账号实际大小写。

### 用户修改和会话清理

用户 Controller 将已认证身份转换为 `UserActor`，用户服务只接收用户 ID、角色和安全版本。客户端不能指定操作人身份。改密、删除和内部角色变更仍在同一 SQL 事务中完成业务修改与版本递增；管理员初始化保留原来的事务隔离级别。

改密成功后，用户 Web 层通过 `SessionService` 退出当前设备。即使退出时 Redis 操作失败，已经提交的 SQL 版本变更仍然有效；其他旧设备在后续鉴权时被拒绝。`SessionCleanupTask` 只负责定时触发，集合扫描、过期判断和失败日志保留在 `ActiveSessions`。

## 依赖边界与设计取舍

- Controller 调用应用服务或必要的 HTTP 适配器，不直接访问 Repository、Redis 或限流存储。认证过滤器和 `AuthService` 可使用本模块的 `RequestLimits`；Controller 不直接使用它。
- 应用层可以使用 Spring 事务、安全抽象、共享业务异常和本模块基础设施，但不依赖 Servlet 或 Controller。`AccountPolicy` 当前通过 `ApiException` 表达 HTTP 业务错误，因此留在应用层。
- 跨业务读取账户只能通过 `user.application.AccountQueryService`。用户应用层不依赖认证模块；认证调用用户服务，不访问用户基础设施。
- 跨业务允许使用公开的应用 DTO、`AccountPolicy` 和 `user.domain.Role`。含哈希的快照只供内部密码认证，不能因为位于 DTO 包就对外返回。
- 用户 Web 层允许使用 `auth.application.dto.SessionPrincipal` 和 `auth.web.session.SessionService`。这是读取已认证身份、改密后退出浏览器会话所需的明确集成点，不扩散到用户应用层。
- 配置类负责装配对象，可以引用本模块的 Web 组件。`RuntimeConfiguration` 当前同时检查运行环境和认证配置，暂放认证配置包；出现其他业务的独立配置需求时再按实际职责拆分。
- `shared` 不依赖 `user` 或 `auth`。只有多个业务实际使用且不含专属业务含义的代码才放进去；认证专用 Redis 工具留在认证模块。

目前不引入完整的领域模型或依赖倒置接口，也不为单一实现创建 Service/ServiceImpl。包本身不能强制访问边界，代码评审仍需检查引用方向。账号状态仍使用数据库字符串；独立状态类型与更通用的运行配置可在实际需求出现时演进。

## 数据库访问约定

Repository 保留业务可读的方法，内部使用 MyBatis-Plus。普通单表操作采用 BaseMapper 和 LambdaWrapper；联表、行锁、状态转换、认证版本递增及维护候选查询放在 XML。应用服务负责事务，不引入通用 IService/ServiceImpl，也不让 Controller 直接调用 Mapper。

数据库实体仅位于基础设施层。用户与身份使用 AUTO 自增 ID，文件使用 INPUT 二进制 UUID；Repository 通过 MediaIds 转换 API 字符串。引用的三列组合主键由专用 Mapper 完整匹配，不伪造单列主键。清空头像及回收时间必须显式写 NULL，不依赖实体更新的默认空值策略。

主库扫描明确绑定 sqlSessionTemplate，XML 位于 mapper/main。通知执行器私有持有连接池、MyBatis-Plus 会话工厂和 SqlSessionTemplate，只加载 mapper/notification，不注册额外数据源或会话工厂 Bean。通知保留自动提交、250ms 连接等待、1秒语句超时与既有网络超时；SQL 超时连接会被淘汰，后续通知使用有效连接，不自动重试原更新。

通知成功要求确认 READY 更新提交；SQL 超时只表示未确认成功，不证明未提交，也不由连接淘汰保证回滚。客户端失败后重新申请新 ID；旧文件可能保留 ISSUED 与原维护时间，也可能已 READY 并带无引用回收时间，均交给 GC，已绑定文件继续受引用保护。不增加数据库确认事务或对象存储即时补偿。该 SQL 竞态不会丢失文件管理记录；极端迟到复制在 GC 完成后生成残留仍属于既有接受边界，不承诺全桶无孤儿。

两个执行器均采用 STATEMENT 本地缓存范围并关闭二级缓存，认证版本和 READ_COMMITTED 引用查询每次都访问数据库。不启用全局逻辑删除、乐观锁或自动填充，以保留业务状态与数据库默认值。测试造数、清理与断言只使用测试专用 Mapper/XML，不扩充生产 Repository 接口。

## 新类放在哪里

| 要增加的能力 | 放置位置与理由 |
|---|---|
| 用户资料修改接口 | `user.web`；HTTP 字段放 `user.web.request`，修改流程放 `user.application` |
| 用户列表数据库查询 | SQL 放 `user.infrastructure.persistence`，跨业务入口由用户应用服务提供 |
| 独立于数据库的用户规则 | `user.domain`；若依赖 HTTP 错误等应用约定，先保留在应用层 |
| 新的认证限流规则 | `auth.infrastructure.ratelimit`，由认证流程或过滤器调用 |
| 文章展示缓存 | `article.content.infrastructure.cache`；按文章ID保存详情和列表，不缓存整页，不增加定时补偿框架 |

测试包通常镜像被测类的包；跨模块的 HTTP 集成测试保留在应用根包。不因为未来可能用到，就创建空包、额外接口或工具层。

### 包注释的写法

每个业务根包和实际使用的子包维护中文 `package-info.java`。先说明具体工作，必要时补一个例子和调用边界；只写当前存在的能力，小包一两句即可。不要只写“承载持久化能力”或“负责用例编排”。

```java
/**
 * 接收用户和管理员的 HTTP 请求，将已认证身份转换成操作人后调用用户服务。
 * 改密成功后通过认证模块的会话适配器退出当前登录，不直接操作数据库。
 */
package com.febrie.backend.user.web;
```

这两份架构文档按 `zh-CN`、`en-US` 分开维护，章节与规则保持对应。代码注释继续使用中文。结构或边界变化时，同时更新包注释和两份文档。

## 文件与实时头像

文件服务已整体切换V2，协议与部署见[文件服务V2](../../../guides/media/zh-CN/media-service.md)。

- MediaService只建档与签发POST Policy；UploadStorage隔离两家签名、一次复制与幂等删除，不提供HEAD确认。
- FinishUpload共用通知身份检查、单调时钟预算、审核、复制和条件READY提交；ContentModeration只返回结论，生产真实审核，开发立即跳过。通知SQL使用独立短等待连接池，网络调用不在业务事务内。
- 存储通知走独立无会话安全链，Filter入口计时、同步分段限量读取并在调用前后检查期限；超时返回504空正文，由COS报告上传端203，不再主动抢占响应；生产COS验签，开发MinIO验证Bearer token。Caddy开发代理只转发上传和统一JSON，不等待发布。
- FileRecord映射八字段文件表，引用表仅三字段；MediaIds统一BINARY(16)与API UUID转换，MediaLocation从固定前缀推导无扩展名Key。
- MediaReferenceService要求显式READ_COMMITTED，调用者先锁业务实体，再按ID排序锁文件；最新引用与当前next_action_at必须在锁内复核。头像唯一由用户行锁事务保证，文章正文与封面去重为ARTICLE_IMAGE引用。
- UserProfileService仅绑定READY，用户头像字段和引用同事务变更；删除用户同时解绑，头像变化不修改认证版本。文件模块不依赖用户基础设施。
- MediaLifecycleService仅清理ISSUED、无引用READY和DELETING，不恢复发布。事务内决定删除，事务外删对象，最后删SQL；删除失败固定间隔调度。
- 通用FixedWindowCounter位于shared.ratelimit，认证规则仍位于auth，上传签发频率由media自己的组件消费；shared不反向依赖业务模块。

Flyway V2定义直接重写，仅用于新建空库。不存在旧complete/prepare、租约、中间状态或兼容模式。文章通过新增V3迁移接入，在锁文章行的同一RC事务中保存内容与引用。非Web初始化不运行定时回收，正常启动不改桶配置。

## 文章保存、查询与缓存

文章接口与维护流程见[文章CRUD接入](../../../guides/article/zh-CN/article-crud-implementation.md)。删除只设置不可恢复的hidden，保留媒体引用；本人列表由article.search按Session作者和公开状态发现候选，移除SQL分页与COUNT；不建立回收站、恢复API或隐藏条目私有装配。

- ArticleService明确禁止外层事务，先经ArticleContentRenderer准备规范JSON、安全HTML、摘要及媒体集合，再经独立ArticleCommitService代理执行一次RC短事务；文章行先于媒体行锁定。最后执行有界缓存维护，缓存异常不覆盖已确认提交。
- 写事务只在文章锁内复核资源权限及hidden，媒体门禁复用MediaReferenceService。无SQL自动重试；元数据版本和Outbox与内容同事务，提交后经MQ同步ES。当前调用内存中的提交确认只用于区分代理提交异常，不是持久化业务状态。
- ArticleQueryService与ArticleListHydrator禁止携带外层事务，缓存获锁后才调用ArticleReader建立短只读RR视图。列表只查小字段，作者通过UserBasicQueryService批量读取公开资料，不读取凭证或排除软删除作者。
- 共享详情与列表投影保存封面ID和Tag，URL、作者名、头像和个人字段在解锁后组装，不进入文章缓存。批量装配按缓存检查、非阻塞取锁、二次检查、合并SQL、逐项token发布分段组织；不持一把锁阻塞等待另一把。
- article.content.infrastructure.cache直接复用Spring Data Redis与固定Lua资源；三态投影、5秒共享锁、双失效、不可自动过期墓碑均限于文章业务。无通用脚本框架或第二套锁库。
- ArticleRequestFilter在消息转换前限制保存字节，统一设置no-store和请求关联ID。游客可读取公开详情、搜索和feed，本人列表与编辑GET仍受保护，写入沿用Session与CSRF。
- ArticleCacheRebuild只在显式启用的非Web命令下重建新命名空间的墓碑；要求先停流量与旧实例，拒绝使用已有Key的版本，不执行FLUSHDB。

新增类按实际职责放在现有层次中，不建空domain包或Service/ServiceImpl。每个实际子包保留中文说明；web不访问Mapper/Redis，article不访问user/media基础设施。

## Redis 原子操作

窗口计数、账号/IP 登录失败计数、会话集合登记分别由三个短 Lua 脚本实现，资源位于 `backend/src/main/resources/redis/`，由 FixedWindowCounter、RequestLimits、ActiveSessions 直接调用。移除 RedisAtomic 通用事务回调；不引入脚本执行框架或新依赖。

Java 负责 Key、窗口、业务阈值与正整数毫秒 TTL；脚本只做固定范围的原子操作。单例 RedisScript 由 Spring Data Redis 创建并缓存 SHA，EVALSHA 缓存缺失时回退 EVAL。计数以字符串返回后转换为 long，避免 Lua 数值精度损失；其余脚本只返回成功标记。每次写入均刷新 TTL，重复会话登记仍成功，超限请求仍计数。

Redis 异常及无效返回沿现有依赖失败处理，不降级放行，不对不确定结果增加自动重试。Lua 不提供错误回滚保证。Key、TTL、数据格式和 Session 命名空间均不因这次迁移改变，无需清库，可直接回滚应用版本。运行账号须允许 EVAL/EVALSHA 及脚本内命令；沿用单 Redis 部署，双 Key 脚本未额外适配 Cluster 同槽约束。

设置 `IT_REDIS=true` 后运行 `mvn '-Dtest=RedisLuaIntegrationTests' test`，使用 `IT_REDIS_HOST/PORT/PASSWORD` 配置，仅连接真实 Redis，不加载 MySQL、Web 或对象存储。测试用随机前缀隔离并清理本次数据，不执行 FLUSHDB 或 SCRIPT FLUSH。

## 会话迁移与验证

`SessionPrincipal` 与 `Role` 移动后，Java 序列化中的类型名称发生变化。Session 命名空间从 `${app.auth.redis-prefix}:session` 改为 `${app.auth.redis-prefix}:session:v2`，避免读取旧类型数据。其他 Redis Key、TTL、SQL 表结构及接口行为不变。

部署时停止旧版本全部实例，再统一启动新版本，避免混合运行。旧登录失效，浏览器重新获取 CSRF 令牌并登录；旧会话自然过期，不清空共享 Redis，不添加旧类型兼容层。回滚也需统一切换实例和配置，恢复旧命名空间可能让尚未过期的旧会话重新可见，应单独评估登录状态。

每批运行 `mvn test`，最终运行 `mvn clean verify`。真实集成测试需显式设置 `IT_DB_PASSWORD`，使用专用 `easy_community_auth_test` 数据库和随机 Redis 前缀；Redis 地址、端口、密码通过测试环境变量提供，不能使用业务数据库。Windows JDK 的临时套接字问题及启动步骤见后端 README。

验证账户查询的一致快照和最新版本、Controller 操作人转换、登录检查顺序与失败计数，以及现有 Cookie、CSRF、角色、改密、删除、并发、撤销和清理行为。本次不新增会话命名空间隔离场景测试。凭证只从本地配置或环境变量读取，不写入代码和文档。

## 文章能力与共享事务边界

文章先按 `content`、`view`、`tag`、`like`、`search` 找能力，再进入对应技术层。`article.common` 只放共用错误和ID解析；内容应用层保留保存、提交、渲染、读取与缓存维护，不为每个服务再建单类目录。浏览统计已接入公开详情，搜索/feed/本人列表只批量读取计数，不增加浏览。

标签目录通过 `TagService.requireAllExist` 提供存在性校验，文章提交不访问标签基础设施；`article_tag` 集合替换与展示JOIN仍属于文章内容持久化。校验沿用调用者事务，不另建事务。文章创建初始化点赞计数仍与正文、标签、媒体引用同事务，点赞入口关闭也必须执行，任何失败一起回滚。这是明确保留的共享数据边界，不改为异步事件。

`ArticleCacheRebuild` 属于内容基础设施维护入口，仍要求非Web模式和未使用的新命名空间。独立提交Bean与短只读Reader保留Spring代理，移动包不合并事务服务。浏览统计的跨能力MySQL测试与内容提交服务同包，以访问内部提交确认类型，不扩大生产可见性。

迁包需同步修改显式Mapper扫描、XML的namespace和结果类型、测试及脚本中的全限定名。Mapper XML按 `mapper/main/article/content` 和 `view` 归组；通知执行器始终不进入主库扫描。HTTP、SQL结构、配置键、Redis Key和消息协议不变。

## 点赞实现与管理端归属

点赞基础设施按 `persistence`、`redis`、`messaging`、`config`、`observability`、`maintenance` 分类。恢复与对账服务位于 `application.maintenance`，保留原有专用连接、命名锁、只读快照和停写要求；命令行入口位于基础设施维护包。现有跨组件测试保留在点赞测试根包，共用命令夹具无需公开。

管理端安全链属于 `shared.management.ManagementSecurityConfiguration`，继续仅允许本机管理端口GET读取health/prometheus，保持原有安全链顺序和Bean方法。分配器健康检查仍属于点赞观测能力，组件名称不变。点赞Mapper XML归入 `mapper/main/article/like`，消息格式、Bean名称、队列、确认与重试协议不变。

## 媒体流程与公开地址

`media.application.upload` 负责申请签发、通知发布、审核契约、通知对象和截止时间，上传DTO随流程放入其dto子包。`reference` 负责调用者事务内的引用变更，`lifecycle` 负责回收；共用文件模型、存储适配和持久化不重复拆分。`UploadStorage` 与 `MediaErrors` 继续作为媒体应用层共用类型。

`MediaUrls` 从上传服务中提取固定公开地址转换，供文章渲染、文章读取、用户资料和上传签发共用。该组件只计算地址，不查询READY状态或访问对象存储，调用者继续承担原有权限和引用前提。`MediaService` 仅承载上传申请，调用方包名与构造参数相应调整，不保留旧包转发。

存储通知的过滤、验签、解析和响应集中在 `media.web.notification`，仍使用原始body/query验签、同一截止时间和独立安全链。通知私有连接池、自动提交、主库排除扫描、引用排序锁及回收事务边界不变。媒体跨组件测试保留共用夹具，通知Filter测试镜像通知包。

包结构调整应验证默认构建、专用真实依赖集成和重构前后行为对照；历史验证结果不代表当前版本已重新通过。执行方式见[后端验证说明](../../../../../backend/README.md#验证)。
