# 本地开发：宿主机应用与 Compose 基础依赖

Spring 在 IDEA 中运行，未来 React/Vue 使用本机 Vite。仓库根目录的 `compose.dev.yaml` 只启动 MySQL、Redis、MinIO、RabbitMQ，以及执行后退出的 `minio-init` 工具，不包含 Caddy、Nginx 或前后端容器。

这是一套全新的独立开发环境，不导入或复用已有数据。现有 3306、6379、9000/9001 服务可以继续运行。

## 首次配置与启动

前提是 Docker Desktop 已启动并切换到 Linux 容器，`docker compose version` 可用。命令均从仓库根目录执行。

```powershell
# 仅首次复制；已有真实文件时直接编辑，不覆盖原配置。
if (-not (Test-Path .env.compose.dev)) {
    Copy-Item .env.compose.dev.example .env.compose.dev
}
if (-not (Test-Path backend/.env.dev)) {
    Copy-Item backend/.env.dev.example backend/.env.dev
}
```

编辑两份真实文件，替换示例密码和 token。Compose 使用 `.env.compose.dev`，Spring 使用 `backend/.env.dev`；两者不会互相加载。需要同步的是解析后的实际值：

| Compose 配置 | Spring 配置 | 约定 |
|---|---|---|
| `DB_USERNAME`、`DB_PASSWORD` | 同名变量 | 默认账号 `ec_dev`，只拥有 `easy_community` 库权限 |
| `MYSQL_PORT` | `DB_URL` 中的端口 | 默认 13306，URL 数据库名保持 `easy_community` |
| `MYSQL_ROOT_PASSWORD` | 无 | 仅容器内数据库管理使用，不用于应用和日常 Navicat |
| `REDIS_PORT`、`REDIS_PASSWORD` | 同名变量 | 默认 16379；`REDIS_HOST=127.0.0.1` |
| `MINIO_API_PORT` | 三个存储 URL 中的端口 | 默认 19000；见下文 |
| `MEDIA_STORAGE_ACCESS_KEY`、`MEDIA_STORAGE_SECRET_KEY` | 同名变量 | 本地应用、初始化工具、MinIO 控制台使用同一开发凭证 |
| `MEDIA_STORAGE_BUCKET`、`MEDIA_KEY_PREFIX` | 同名变量 | 默认 `easy-community-dev`、`easy-community/dev` |
| `MEDIA_WEBHOOK_TOKEN` | 同名变量 | 非空、不能包含空白的单段 token |
| `MEDIA_FRONTEND_ORIGIN` | 同名变量 | 默认 `http://127.0.0.1:5173`，按未来 Vite 实际来源设置 |
| `SERVER_PORT` | 同名变量 | 默认 8080，供 MinIO 定位宿主机后端 |
| `RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD` | 暂无 | 未来接入消息业务时使用，vhost 为 `easy_community_dev` |

Compose 环境文件会处理插值和引号，包含 `$`、`#` 等字符的值可用单引号包围；单引号本身按 Compose 语法转义。Spring 环境文件按 Java Properties 读取，**不要添加包围引号**，不支持 shell 变量展开，反斜杠需要写成双反斜杠。不要将其中一份文件直接当另一份使用；系统环境变量会优先于文件配置。

```powershell
docker compose --env-file .env.compose.dev -f compose.dev.yaml config --quiet
docker compose --env-file .env.compose.dev -f compose.dev.yaml up -d
docker compose --env-file .env.compose.dev -f compose.dev.yaml ps -a
docker compose --env-file .env.compose.dev -f compose.dev.yaml logs minio-init
```

四项长期服务应变为 `healthy`；`minio-init` 应为 `Exited (0)` 并输出开发桶初始化完成。`up -d` 返回不代表所有初始化已结束；失败时先看对应服务日志，不要通过清卷排错。初始化不要求 Spring 已经启动。

## IDEA、Navicat 与管理入口

IDEA 导入 `backend/pom.xml`，选择 JDK 25，在 `BackendApplication` 的 Program arguments 设置 `--spring.profiles.active=dev`。Working directory 可为仓库根目录或 `backend`，保存后直接运行。也可执行：

```powershell
Set-Location backend
mvn spring-boot:run '-Dspring-boot.run.profiles=dev'
```

MySQL 容器负责创建空库，Spring 首次启动由 Flyway 建表，不手动导入建表 SQL。数据库使用 UTC、`utf8mb4`；用户名字段自身的大小写规则仍由现有迁移定义。初始化管理员沿用 [后端说明](../../backend/README.md#初始化管理员)。

| 入口 | 地址与账号 |
|---|---|
| Navicat / MySQL | 主机 `127.0.0.1`，端口 `13306`，用户 `ec_dev`，密码为 `DB_PASSWORD`，数据库 `easy_community` |
| Redis | `127.0.0.1:16379`，密码为 `REDIS_PASSWORD` |
| MinIO API | `http://127.0.0.1:19000` |
| MinIO 控制台 | `http://127.0.0.1:19001`，使用 `MEDIA_STORAGE_ACCESS_KEY` / `MEDIA_STORAGE_SECRET_KEY` |
| RabbitMQ 管理台 | `http://127.0.0.1:15672`，使用 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` |
| RabbitMQ AMQP | `127.0.0.1:5672`，vhost `easy_community_dev` |

Navicat 使用常规 TCP 连接，不需要 SSH 隧道或容器 IP；本地连接无需 SSL。使用支持 MySQL 8 `caching_sha2_password` 的客户端，不为旧客户端降级数据库认证。Spring 示例 URL 的 `sslMode=DISABLED&allowPublicKeyRetrieval=true` 仅用于本地回环地址，不复制到生产。

## MinIO 初始化与上传边界

初始化工具通过容器网络的 `minio:9000` 调用 S3 API，自动创建开发桶，并整体设置该桶的策略、生命周期和通知；它只适用于本项目专用开发桶。重复执行保留对象，配置不会重复叠加。桶名限定 3 至 63 位小写字母、数字、连字符，首尾为字母或数字；对象前缀遵循应用校验且必须以 `/dev` 结尾。

- 只允许匿名读取 `{MEDIA_KEY_PREFIX}/public/*`，不开放列桶、写入或临时对象读取。
- `{MEDIA_KEY_PREFIX}/temp/` 设置一天过期，实际删除由 MinIO 生命周期扫描完成。
- 只订阅临时前缀的 `s3:ObjectCreated:Post`，不将后端复制公开对象产生的事件再次通知。
- 回调地址为 `http://host.docker.internal:<SERVER_PORT>/api/storage/minio/upload-webhook`，使用 `MEDIA_WEBHOOK_TOKEN` 认证。后端设置 `SERVER_ADDRESS=0.0.0.0` 以允许容器访问，Windows 防火墙需允许该 Java 进程的 Docker 回调连接。

宿主机的三个开发地址为：

```properties
MEDIA_STORAGE_ENDPOINT=http://127.0.0.1:19000
MEDIA_STORAGE_UPLOAD_ENDPOINT=http://127.0.0.1:19000
MEDIA_PUBLIC_BASE_URL=http://127.0.0.1:19000/easy-community-dev
```

本次没有浏览器上传代理。MinIO 原生 POST 响应不符合现有 `backend/examples/media/upload.mjs` 要求的统一 JSON，因此该示例暂不能直接用于这套环境。可以使用授权返回的字段发起底层 multipart POST，验证对象写入和 Webhook 发布；不要据此放宽前端成功判定。后续接入 Vite 时单独实现上传响应适配，普通 `/api` 代理不会自动完成这一转换。

原有 `backend/examples/media/compose.dev.yaml` 与 Caddy 配置保留为历史上传联调示例，使用相同 19000/19001 端口，**不能与新 Compose 同时启动**；新环境不执行该示例。完整业务协议见 [文件服务说明](media-service.md)。

## 日常管理与数据保留

以下命令仍在仓库根目录执行：

```powershell
# 状态、日志及停止；down 默认保留命名卷。
docker compose --env-file .env.compose.dev -f compose.dev.yaml ps -a
docker compose --env-file .env.compose.dev -f compose.dev.yaml logs --tail 100 mysql redis minio rabbitmq minio-init
docker compose --env-file .env.compose.dev -f compose.dev.yaml stop
docker compose --env-file .env.compose.dev -f compose.dev.yaml down

# 再次启动；需要重新应用开发桶配置时，显式重跑初始化工具。
docker compose --env-file .env.compose.dev -f compose.dev.yaml up -d
docker compose --env-file .env.compose.dev -f compose.dev.yaml run --rm minio-init
```

四个命名卷均由 `easy-community-dev` 项目隔离。Redis 开启 AOF；RabbitMQ 节点名称固定。不要修改项目名、RabbitMQ hostname 或节点名来“修复”连接，否则可能连接到另一套数据目录。

修改端口后更新上表对应的 Spring URL/端口及 Navicat 连接，再执行 `up -d`；修改后端端口还需重建 MinIO，使回调配置生效。已有文件时不要随意更换桶名或对象前缀，SQL 不记录旧定位配置。

MySQL 和 RabbitMQ 的初始化账号只在新数据卷首次创建时生效；修改环境文件不会自动修改已有卷内密码。Redis 重建后会读取新密码，MinIO 重建后会读取新根凭证；每次调整都要同步 Spring 配置。已有 MySQL/RabbitMQ 数据需要通过管理命令修改账号，而不是只修改文件。

只有明确放弃**本项目全部开发数据**时才执行下列重置命令，它会删除这套 Compose 的四个数据卷：

```powershell
docker compose --env-file .env.compose.dev -f compose.dev.yaml down --volumes
```

不使用 `docker volume prune` 等全局清理命令。镜像已固定 digest；升级时明确更新镜像版本和 digest，再验证数据兼容性。

## 验证建议

先确认服务健康及初始化成功，再运行后端，检查 Flyway、`GET /api/auth/csrf` 和 Redis 会话。上传验证检查底层 POST、Webhook 发布、公开对象可读、临时对象匿名读取被拒绝；当前浏览器示例不作为本次验收项。

普通单元测试使用 `mvn test`。现有数据库集成测试保留自己的默认端口和专用测试库约定；连接这套 MySQL 时必须显式配置 `IT_DB_URL`、`IT_DB_USERNAME`、`IT_DB_PASSWORD` 和 `IT_REDIS_*`，数据库名使用测试要求的 `easy_community_*test*` 专用库，不能指向 `easy_community`。本次 Compose 不自动创建测试库或拓宽开发账号权限。
