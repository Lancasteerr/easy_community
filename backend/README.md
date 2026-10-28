# 社区后端：Session 登录、用户与文件管理

代码组织与包职责见架构文档：[简体中文](../docs/zh-CN/backend-architecture.md) · [English](../docs/en-US/backend-architecture.md)。

文件上传、MinIO/COS桶配置、头像接口和文章接入见[文件服务V1](../docs/zh-CN/media-service.md)。
后续重设计见[文件服务V2设计：上传回调、同步审核与延迟回收](../docs/zh-CN/media-service-v2-design.md)，其中包含本地MinIO同步上传网关方案；当前代码仍为V1。
升级已有本机环境文件时，必须补充示例中的全部`MEDIA_*`字段；应用只校验配置，不自动创建桶或修改权限。

Java 25 / Spring Boot 4.1.1 / MySQL 8 / Redis。首次启动前创建空库 `easy_community`，Flyway 自动建表。
只新增已确认的官方 `spring-boot-starter-session-data-redis`，移除了 JWT Resource Server starter；无 Lua、第三方 JWT 或限流依赖。

## 本地启动

必须显式选择 `dev` 或 `prod`，无默认运行环境。选定环境后自动读取对应的 `.env.dev` 或 `.env.prod`，数据库密码不写入仓库。

```powershell
# 在 backend 目录首次配置；编辑 .env.dev，填写本机数据库密码。
Copy-Item .env.dev.example .env.dev
# 配置完成后直接启动，无需每次手动设置数据库密码环境变量。
mvn spring-boot:run '-Dspring-boot.run.profiles=dev'
```

IDEA 首次启动：将 `backend/pom.xml` 添加为 Maven 项目，使用 JDK 25；
在 `BackendApplication` 的运行配置中设置 Program arguments 为 `--spring.profiles.active=dev`，保存后即可点击运行。
Working directory 可以是仓库根目录或 `backend` 目录，环境文件统一放在 `backend` 内；不需要安装 EnvFile 插件。

| 文件 | 用途 | 是否提交 |
|---|---|---|
| `.env.dev.example` | 开发环境配置模板 | 是 |
| `.env.prod.example` | 生产环境配置模板 | 是 |
| `.env.dev` | 本机开发连接和密码 | 否 |
| `.env.prod` | 服务器生产连接和密码 | 否 |

环境先由启动参数或系统环境变量 `SPRING_PROFILES_ACTIVE` 选择，再加载对应文件；不要在这些文件内设置运行环境。
两种环境不会主动加载对方的文件。没有环境文件时，仍可只使用系统环境变量启动，适合容器部署。
文件使用 Java Properties 语法：`KEY=value`，无需给值添加引号，不支持 `export` 或 shell 变量展开；
反斜杠需写成双反斜杠，密码中的 `$`、`#`、`=` 可直接出现在值中，避免在值前后添加无意义空格。
系统环境变量优先于文件中的同名变量。不要同时在仓库根目录和 `backend` 目录存放同一环境文件；两处都存在时后者优先。
此方式使用 [Spring Boot 配置导入](https://docs.spring.io/spring-boot/reference/features/external-config.html)，文件中的键属于应用配置，不会写入操作系统环境变量。

开发环境默认 MySQL `127.0.0.1:3306/easy_community`、用户名 `root`、Redis `127.0.0.1:6379`。
可覆盖 `DB_URL / DB_USERNAME / DB_PASSWORD / REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / SERVER_PORT`。
本地 HTTP Cookie 不设置 Secure；所有环境均为 HttpOnly、Host-only、Path=/、SameSite=Lax。

如果Windows Java出现 `Unable to establish loopback connection` 且堆栈含 `UnixDomainSockets`，
可给JVM指定已存在的普通临时目录，例如 `-Djdk.net.unixdomain.tmpdir=E:/temp`；这是本地运行参数，不进入生产配置。
Maven测试对应参数为 `mvn '-DargLine=-Djdk.net.unixdomain.tmpdir=E:/temp' test`。

## 初始化管理员

先构建JAR。初始化仅允许非Web模式，已经存在ADMIN时拒绝重复初始化，没有默认密码和公网初始化接口。
初始化事务使用SERIALIZABLE；并发初始化最多一笔成功，另一笔可能因冲突或死锁失败，不自动重试。

```powershell
# 数据库连接沿用 .env.dev；管理员初始凭证通过临时环境变量传递。
mvn -DskipTests package
$env:ADMIN_USERNAME = '<管理员用户名>'
$env:ADMIN_PASSWORD = '<管理员初始密码>'
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.bootstrap.enabled=true
Remove-Item Env:ADMIN_USERNAME,Env:ADMIN_PASSWORD
```

## 浏览器接入

前端使用Vite的 `server.proxy`，示例见 `examples/vite.config.ts`，不剥离 `/api`。
开发流程为浏览器 → Vite:5173 → 后端:8080；前端始终请求相对路径，浏览器不跨Origin访问后端。
不要混用localhost和127.0.0.1访问前端，否则浏览器会分别保存Cookie。

```javascript
// 登录前先获取会话对应的CSRF；不要将Session ID放入localStorage或JSON。
let csrf = await fetch('/api/auth/csrf', { credentials: 'same-origin' }).then(r => r.json());
const login = await fetch('/api/auth/login', {
  method: 'POST', credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
  body: JSON.stringify({ username: 'alice', password: '<密码>' })
});
// 登录会轮换Session ID，成功后必须重新取得CSRF，再调用修改接口。
if (login.ok) csrf = await fetch('/api/auth/csrf').then(r => r.json());
```

`/csrf`响应为 `{ "token": "...", "headerName": "X-CSRF-TOKEN" }`，Token使用框架默认BREACH掩码，原样提交。
所有修改接口，包括登录、退出，必须提供CSRF请求头。401后回到登录流程；403可能是权限不足或CSRF失效，重新获取Token后再由用户发起操作。
认证后2小时绝对过期，不随访问延长；匿名会话15分钟空闲过期。不提供公开注册、刷新Token、自助找回密码或管理员重置密码。

## 接口

| 方法与路径 | 请求/响应 | 权限 |
|---|---|---|
| GET `/api/auth/csrf` | 返回token、headerName；禁止缓存 | 匿名可访问 |
| POST `/api/auth/login` | `{username,password}` → `{userId,username,role}` | 匿名可访问，需CSRF |
| POST `/api/auth/logout` | 无请求体，204 | 已登录，需CSRF |
| GET `/api/users/me` | `{userId,username,role}` | 已登录 |
| PUT `/api/users/me/password` | `{oldPassword,newPassword}`，204，全部设备失效 | 已登录，需CSRF |
| POST `/api/admin/users` | `{username,password,role?}`，201返回用户信息 | ADMIN，需CSRF |
| DELETE `/api/admin/users/{userId}` | 逻辑删除，204，重复删除仍204 | ADMIN，需CSRF |

ADMIN只允许创建/删除USER和PATROLLER；role省略默认USER，不能通过该接口创建或删除ADMIN。
PATROLLER目前没有用户管理权限，巡查权限留待内容模块。每用户单角色，不提供修改角色的HTTP接口。
用户名去首尾空白、保留大小写，必须匹配 `[A-Za-z0-9_]{4,32}`，创建后不可修改；创建判重和登录均区分大小写。
`Alice_1` 与 `alice_1` 可以是两个独立账号，登录必须匹配实际大小写；删除后原用户名仍不可复用。
用户名大小写规则直接更新 V1 建表定义中的注释，按新建数据库使用，不提供旧数据迁移或已有 Flyway 记录的兼容处理。
userId为不可修改的自增BIGINT，JSON中使用字符串。密码至少8个Unicode字符，UTF-8最多72字节，使用强度12的BCrypt，不截断、不去空白。

错误采用 `application/problem+json`，带稳定 `code`：

| HTTP | code及含义 |
|---|---|
| 400 | INVALID_REQUEST、INVALID_USERNAME、INVALID_PASSWORD、OLD_PASSWORD_MISMATCH、PASSWORD_UNCHANGED |
| 401 | INVALID_CREDENTIALS（不区分账号不存在/密码错误/账号不可用）、AUTHENTICATION_REQUIRED |
| 403 | ACCESS_OR_CSRF_DENIED、ADMIN_OPERATION_FORBIDDEN |
| 404 | USER_NOT_FOUND |
| 409 | USERNAME_EXISTS、ADMIN_ALREADY_INITIALIZED |
| 429 | RATE_LIMITED，响应头Retry-After为剩余秒数 |
| 503 | DEPENDENCY_UNAVAILABLE，SQL或Redis不可用时不降级放行 |

## 版本与Redis

SQL是账号状态和authVersion的唯一权威；`users`和`user_auth_identity`在同一数据库。
登录单条JOIN读取哈希、角色、状态、版本，验证密码后保存同一份快照。
改密、删号、内部角色更新必须与版本递增同事务提交，禁止先改字段、稍后单独递增版本。
每次受保护请求读取主库status和authVersion；一致时直接使用Session中的role，不重新读取role、不自动升级Session版本。
已通过鉴权的请求允许完成；本实现保证账号更新提交后的后续鉴权拒绝旧版本，主要防止旧会话长期活跃。
改密和删除以SQL提交为准；若提交后Redis清理失败，即使请求返回503，旧版本也已失效，不会回滚SQL恢复旧凭证。

| Key | 类型 | 生命周期 |
|---|---|---|
| `ec:<env>:session:v2:sessions:<sessionId>` | Hash | 框架会话；认证主体含userId、username、role、authVersion、authenticatedAt、expiresAt |
| 同Hash中的 `sessionAttr:auth.csrfToken` | 框架序列化属性 | 同Session，不另建CSRF副本 |
| 同Hash中的 `sessionAttr:auth.expiresAt` | 毫秒时间戳属性 | 即使框架续TTL，也必须校验绝对到期 |
| `ec:<env>:auth:user:<userId>:v:<version>:sessions` | Set | member=Session ID；仅登录SADD，TTL为2小时 |
| `ec:<env>:auth:login-fail:account:<sha256>:<window>` | String | 15分钟窗口，TTL16分钟 |
| `ec:<env>:auth:login-fail:ip:<sha256>:<window>` | String | 15分钟窗口，TTL16分钟 |
| `ec:<env>:auth:request:<rule>:<sha256>:<window>` | String | 1分钟窗口，TTL为窗口剩余时间加60秒 |

Session存在、绝对未过期、SQL为ACTIVE、版本一致、Set包含Session ID五个条件必须同时成立。
只有登录登记成员；退出先SREM再删除Session，因此迟到Hash回写不会复活登录。
迟到登录使用旧版本桶，无法进入新版本的权限范围；旧桶自然到期即可，不依赖批量删除成功。
每10分钟SCAN/SSCAN分批清理不存在或绝对过期的成员，SCARD不代表精确在线人数。
Redis使用Spring Session默认序列化，运行实例使用一致应用版本；Redis不得对不可信写入者开放。
本次认证类型分包后，Session 命名空间切换为 `ec:<env>:session:v2`。升级时停止全部旧实例后统一启动新版本，
浏览器重新获取 CSRF 并登录；旧会话自然过期，不清空共享 Redis。迁移及回滚注意事项见架构文档。
无Lua；仅用普通原子命令及MULTI/EXEC保持计数/集合写入和TTL同事务。

默认限流：CSRF 60次/IP/分钟，登录30次/IP/分钟，已登录请求120次/userId/分钟，
认证失败5次/账号/15分钟及30次/IP/15分钟。账号失败计数使用去首尾空白且保留大小写的用户名，大小写不同的账号分别计数，IP 限流仍共享。
成功登录不清空失败计数，不撤回已进入认证的并发请求。
固定窗口存在跨窗口边界突发，不宣称是滑动窗口或令牌桶算法。
覆盖变量为 `AUTH_CSRF_PER_MINUTE / AUTH_LOGIN_PER_MINUTE / AUTH_USER_PER_MINUTE / AUTH_ACCOUNT_FAILURES / AUTH_IP_FAILURES`，均须大于零。

## 生产代理与容器配置

拓扑：浏览器 HTTPS → Caddy → Nginx → 后端。只公开Caddy，Nginx和后端仅在专用容器网络可达。
`examples/Caddyfile`与`examples/nginx.conf.template`给出转发约定；根据实际容器网段填写，不直接套用示例IP。

- Caddy丢弃客户端提供的Forwarded、X-Real-IP，覆盖X-Forwarded-For/Proto/Host。
- Nginx只信任Caddy地址恢复真实IP，然后用 `$remote_addr` 覆盖后端收到的XFF；保留外部HTTPS协议信息。
- 后端prod使用Tomcat NATIVE处理，仅信任 `TRUSTED_PROXY_REGEX` 中明确的Nginx地址。业务统一读取getRemoteAddr，不自行取XFF第一项。
- dev使用NONE，Vite代理到后端时计入回环地址属于预期；本地多浏览器共享IP限额，可显式调整dev运行阈值。
- 生产 `DB_URL / DB_USERNAME / DB_PASSWORD / REDIS_HOST / REDIS_PASSWORD / TRUSTED_PROXY_REGEX` 必填；Redis无密码时也须显式提供空值。
- 容器设置 `SPRING_PROFILES_ACTIVE=prod`，不在镜像内保存凭证，不把后端8080发布到宿主公网。
- `TRUSTED_PROXY_REGEX` 示例：仅Nginx为172.31.0.2时使用 `172\.31\.0\.2`；不得配置 `.*` 或信任所有私网。

首次部署数据库需预先创建，生产应用账号只授予业务库所需权限；迁移与运行目前共享数据源。
直接运行 JAR 时，在服务器工作目录中由 `.env.prod.example` 创建 `.env.prod` 并填写实际值，
执行 `java -jar /path/to/backend.jar --spring.profiles.active=prod`。
容器可以由部署工具将 `.env.prod` 注入为系统环境变量，或者只读挂载到容器工作目录的 `.env.prod`，不要将文件打入镜像。
生产默认同源API，不配置通配CORS。未来真实跨站部署需要另行确认Cookie的SameSite及来源白名单。

## 验证

普通 `mvn test` 执行单元测试；真实集成测试需显式提供 `IT_DB_PASSWORD`。
先创建专用空库 `easy_community_auth_test` 和 `easy_community_media_test`，启动Redis，再运行：

```powershell
# 可覆盖IT_DB_URL、IT_DB_USERNAME、IT_REDIS_HOST、IT_REDIS_PORT、IT_REDIS_PASSWORD。
$env:IT_DB_PASSWORD = '<测试库密码>'
mvn test
Remove-Item Env:IT_DB_PASSWORD
```

集成测试自动执行Flyway迁移、使用随机Redis前缀、清理本次创建的用户，不清空共享Redis。
文件引用、头像事务和HTTP测试使用`easy_community_media_test`；真实MinIO/COS验收所需的`IT_MINIO_*`、`IT_COS_*`参数见文件服务文档，未配置时相应契约测试跳过。
不要把IT_DB_URL指向业务数据库。测试覆盖HTTP Cookie/CSRF、角色、改密、删号、并发认证、Set撤销、过期清理和计数事务。

参考：[Spring Session](https://docs.spring.io/spring-session/reference/guides/boot-redis.html)、
[Spring CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)、
[Tomcat代理配置](https://docs.spring.io/spring-boot/how-to/webserver.html)、
[Nginx realip](https://nginx.org/en/docs/http/ngx_http_realip_module.html)、
[Caddy代理](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)。
