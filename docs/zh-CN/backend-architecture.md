# 后端代码组织与架构约定

[English](../en-US/backend-architecture.md) · [启动、接口和部署说明](../../backend/README.md)

## 从业务找到代码

后端保持单个 Maven 工程。先按用户、认证等业务找包，再按接口、业务流程和数据库操作找子包。这样一次用户需求涉及的代码通常集中在 `user`，而不是散落在全局 Controller、Service 和 Mapper 目录中。

包根为 `com.febrie.backend`：

```text
backend
├─ BackendApplication
├─ user
│  ├─ web
│  │  └─ request
│  ├─ application
│  │  └─ dto
│  ├─ domain
│  └─ infrastructure
│     ├─ persistence
│     └─ bootstrap
├─ auth
│  ├─ web
│  │  ├─ request
│  │  ├─ response
│  │  ├─ session
│  │  └─ security
│  ├─ application
│  │  └─ dto
│  └─ infrastructure
│     ├─ config
│     ├─ security
│     ├─ session
│     ├─ ratelimit
│     ├─ redis
│     └─ scheduling
└─ shared
   ├─ error
   └─ web
```

这是一种实用分层，不要求每个业务都有四层。当前认证没有独立的纯业务模型，因此不建 `auth.domain`；未来新增帖子、评论时，同样按实际代码建包。

## 包名怎么理解

| 包名 | 放什么 | 当前例子 |
|---|---|---|
| `web` | 接收 HTTP 请求、读取已认证身份、返回结果 | `UserController`、`AuthController` |
| `application` | 组织一次操作的步骤和事务，提供其他业务可调用的服务 | 创建用户、改密、登录流程、账户查询 |
| `domain` | 不依赖数据库、HTTP 和框架的业务类型或规则 | 公开的 `Role` 枚举 |
| `infrastructure` | 数据库、Redis、安全配置等具体实现 | 账户 SQL、限流计数、会话集合 |
| `persistence` | 从数据库读取和保存数据 | `AccountRepository` 使用 JdbcTemplate |
| `dto` | 服务与调用方之间传递的数据；DTO 指数据传输对象 | `UserActor`、`UserView`、`AccountAuthState` |
| `request` / `response` | 某类 HTTP 接口的请求、响应对象 | `LoginRequest`、`CsrfView` |
| `scheduling` | 安排定时任务何时执行 | `SessionCleanupTask` |
| `shared` | 多个业务共同使用、又不属于某个业务的基础能力 | `ApiException`、统一错误响应 |

同名单词还需结合父包理解：`auth.web.session` 操作 HTTP 会话和 Cookie，`auth.infrastructure.session` 操作 Redis 中的有效会话集合；`auth.web.security` 在请求进入接口前校验身份，`auth.infrastructure.security` 接入密码认证提供器。

## 现有流程如何协作

### 登录和请求校验

1. `SecurityRequestFilter` 在 CSRF 检查前限制登录入口的请求频率，只读取容器处理后的客户端 IP。
2. `AuthController` 接收参数，将用户名、原始密码和 IP 交给 `AuthService`。
3. `AuthService` 规范化用户名、检查失败次数，再调用 Spring Security 的认证管理器。仅认证失败增加失败计数，数据库或 Redis 故障继续向外传播。
4. `LocalAuthenticationProvider` 通过 `AccountQueryService` 调用用户模块；用户模块使用一次 JOIN 读取密码哈希、角色、状态和安全版本。账号不存在时仍执行一次密码哈希匹配。
5. 验证成功后，`SessionService` 轮换 HTTP 会话，立即保存认证快照，再登记 Redis 集合成员。Controller 返回不含凭证的用户信息。
6. 后续受保护请求由 `SessionValidation` 检查绝对到期时间、主库账户状态、安全版本及集合成员资格，不重新加载角色，也不自动升级会话版本。

`AccountSnapshot` 含密码哈希，只能用于内部认证，不能作为 HTTP 响应或写入 Session。`SessionPrincipal` 保存登录时确定的身份和到期时间，不包含密码。HTTP 用户信息使用 `UserView`，用户 ID 保持字符串。

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

## 新类放在哪里

| 要增加的能力 | 放置位置与理由 |
|---|---|
| 用户资料修改接口 | `user.web`；HTTP 字段放 `user.web.request`，修改流程放 `user.application` |
| 用户列表数据库查询 | SQL 放 `user.infrastructure.persistence`，跨业务入口由用户应用服务提供 |
| 独立于数据库的用户规则 | `user.domain`；若依赖 HTTP 错误等应用约定，先保留在应用层 |
| 新的认证限流规则 | `auth.infrastructure.ratelimit`，由认证流程或过滤器调用 |
| 帖子缓存和过期任务 | 需求实现时建立 `post.infrastructure.cache`、`post.infrastructure.scheduling`，不放进全局工具包 |

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

## 会话迁移与验证

`SessionPrincipal` 与 `Role` 移动后，Java 序列化中的类型名称发生变化。Session 命名空间从 `${app.auth.redis-prefix}:session` 改为 `${app.auth.redis-prefix}:session:v2`，避免读取旧类型数据。其他 Redis Key、TTL、SQL 表结构及接口行为不变。

部署时停止旧版本全部实例，再统一启动新版本，避免混合运行。旧登录失效，浏览器重新获取 CSRF 令牌并登录；旧会话自然过期，不清空共享 Redis，不添加旧类型兼容层。回滚也需统一切换实例和配置，恢复旧命名空间可能让尚未过期的旧会话重新可见，应单独评估登录状态。

每批运行 `mvn test`，最终运行 `mvn clean verify`。真实集成测试需显式设置 `IT_DB_PASSWORD`，使用专用 `easy_community_auth_test` 数据库和随机 Redis 前缀；Redis 地址、端口、密码通过测试环境变量提供，不能使用业务数据库。Windows JDK 的临时套接字问题及启动步骤见后端 README。

验证账户查询的一致快照和最新版本、Controller 操作人转换、登录检查顺序与失败计数，以及现有 Cookie、CSRF、角色、改密、删除、并发、撤销和清理行为。本次不新增会话命名空间隔离场景测试。凭证只从本地配置或环境变量读取，不写入代码和文档。
