# 文件服务 V2：POST 直传、通知发布与引用回收

当前实现采用 [V2 设计](media-service-v2-design.md)。生产通过 COS Callback 同步审核、复制并提交 READY；开发经 Caddy 上传代理直传 MinIO，由 Webhook 发布。只支持新建空库，不提供旧协议、旧表和 Flyway 历史兼容。

## 数据与职责

- `media_file` 恰好八字段：id、uploader_id、purpose、status、mime_type、size_bytes、created_at、next_action_at。
- `media_reference` 恰好三字段：file_id、ref_type、ref_id，复合主键 `(ref_type,ref_id,file_id)`，文件外键 ON DELETE RESTRICT。
- UUID 在 SQL 中为标准高低位字节顺序的 BINARY(16)，API 与 Key 为标准小写字符串。
- Key 固定为 `{prefix}/temp/{id}` 与 `{prefix}/public/{id}`，无扩展名；桶、厂商与前缀不落 SQL。已有文件后不能热切换定位配置。
- 只有 ISSUED、READY、DELETING 三状态。失败保持 ISSUED，不保存失败原因或处理中状态。
- `size_bytes` 来自已认证通知的实际对象长度。当前审核适配器不提供真实格式结论，`mime_type` 保持 NULL；复制时的 Content-Type 仍来自受 Policy 约束的声明。
- 公开图片知道 URL 即可读取，隐藏文章不意味着其图片保密。

## 上传与浏览器接入

`POST /api/media/uploads` 使用 Session + CSRF，响应 201。

```json
{"purpose":"AVATAR","filename":"avatar.png","contentType":"image/png","size":12345}
```

用途为 AVATAR（2 MiB）或 ARTICLE_IMAGE（10 MiB）。声明 MIME 仅接受 image/jpeg、image/png、image/webp。文件名只校验展示用途，不保存、不参与 Key。

```json
{
  "mediaId":"6de1cddb-a81d-4c1f-bb6e-9bf9606eeeca",
  "publicUrl":"https://images.example.com/easy-community/prod/public/6de1cddb-a81d-4c1f-bb6e-9bf9606eeeca",
  "upload":{"method":"POST","url":"https://bucket.cos.ap-guangzhou.myqcloud.com/","fields":{},"expiresAt":"2026-10-29T12:05:00Z"}
}
```

实际 fields 包含全部 Policy 和签名字段。前端逐项原样放入 FormData，最后放 file；不手动设置请求级 Content-Type，以 `credentials: omit` 上传。示例为 `backend/examples/media/upload.mjs`，可直接导入 uploadMedia 或 uploadFile。社区 API 使用同源 Cookie 和 CSRF，存储请求不发送社区 Cookie。

仅 HTTP 200 且 JSON 明确 `success:true` 才成功，文件身份与 URL 取自授权：

```json
{"success":true,"stage":"COMPLETE","code":"SUCCESS"}
```

失败结构同形，例如 `{"success":false,"stage":"MODERATION","code":"MODERATION_REJECTED"}`。前端不区分环境，不解析 MinIO 特例，不接受 203、空正文、XML 或网络错误为成功，不自动重传同一授权。用户主动重试时重新申请新 ID。没有 complete、prepare、状态查询、固定等待或自动恢复。

开发成功只表示 MinIO 接收成功，可能早于公开可读与 READY。图片暂不可读可稍后刷新；即时绑定仍可能返回 409，按正常操作稍后重试，不能放宽 READY 约束。

## 发布与协作式超时

生产仅注册 `POST /api/storage/cos/upload-callback`，开发仅注册 `POST /api/storage/minio/upload-webhook`。独立安全链不加载 Session，不要求 CSRF，不执行用户限流。无效社区 Cookie 不影响通知。错误环境的通知入口返回 404。

COS 根据原始 body、路径、query 与约定密钥验证 `x-cos-callback-encrypted-auth`；JSON 解析发生在验签之后。反向代理必须保留完整路径、query 与正文，不把 Referer 或 User-Agent 当成认证。Callback 白名单与 ckey 需提前在腾讯云开通和配置。

共用流程为：身份、状态与期限检查 → 同步审核 → CopyObject → 条件 UPDATE READY。审核与复制不在 SQL 事务内，均只调用一次，关闭 SDK 普通、复制专用及备用端点重试。生产对私有 ObjectKey 使用固定 biz-type，超过 5 MiB 开启 large-image-detect；非明确通过不发布。官方 SDK 完整解析复制响应，不只检查 HTTP 200。

通知Filter最开始记录一次System.nanoTime()，正文读取、验签和后续调用共用默认4.5秒预算。正文同步分段限量读取，调用前后检查期限；不使用Servlet异步、主动截止定时器、取消标记或响应竞争控制，也不再固定预留1250ms。阻塞读取由容器与代理读超时管理，不承诺处理线程恰好在第五秒退出。COS每次操作独占ClientConfig，MinIO使用请求级总超时，审核与复制SDK使用剩余预算且不重试；通知连接池最多四条连接、获取等待250ms、网络与语句上限1秒，SQL返回后仍须检查迟到。预算不落库，未过期的剩余时间可以继续使用。

生产仅在复制完成、READY UPDATE 影响一行且提交成功后返回成功。非超时业务失败也用HTTP200、小型JSON和准确Content-Length，认证或格式失败用4xx。整体期限耗尽或SDK/SQL明确超时返回HTTP504、Content-Length: 0，不生成200超时JSON；由COS向上传客户端生成203 CallbackFailed。超时的阶段、PROCESS_TIMEOUT和耗时只记日志。结果头为 X-Media-Id、X-Media-Result、X-Media-Stage、X-Media-Code；浏览器以 JSON 为准，不能假定 COS 透传自定义回调头。

复制成功但 SQL 失败留下的孤儿由 ISSUED 回收。响应丢失可能实际已 READY，客户端仍按失败重新上传，旧对象无引用时回收。不恢复审核或复制，不立即补偿删除；极端迟到复制残留沿用设计接受的边界。

## 引用、头像与文章契约

头像设置和清除保持 `PUT/DELETE /api/users/me/avatar`。设置请求为 `{"mediaId":"标准UUID"}`，只能绑定当前上传者的 READY AVATAR；资料与引用同事务更新，不执行存储操作，不改变 authVersion。

引用必须在显式 READ_COMMITTED 事务中维护，先锁业务实体，再按文件 ID 顺序锁新旧文件。文件归属、用途、状态、可信大小在文件锁内检查。头像唯一靠用户行锁保证，复合主键本身不保证每用户一个头像。

未来文章调用 `MediaReferenceService.syncArticle(articleId, owner, bodyIds, coverId)`：调用者先验证权限并锁定真实文章行，保存正文、封面与引用处于同一事务。正文和封面合并去重，统一 ARTICLE_IMAGE 用途与引用类型；封面额外限制 5 MiB。本次不建立文章表或文章 HTTP 接口。

首次 READY 安排 24 小时无引用宽限期；引用后 next_action_at 为 NULL；最后解绑才重新安排宽限期。重复保存不延长时间，多实体共用文件时一处解绑不能触发回收。

GC 每五分钟按三个状态分别扫描最多 100 个候选。加文件行锁后重查当前状态、当前到期时间和最新引用，再提交 DELETING。这样扫描后绑定又解绑得到新宽限期的文件不会被提前删除。远程删除在事务外，顺序为删除公开 Key → 删除无引用 SQL 行。删除失败固定五分钟后再尝试；无租约、重试次数、墓碑或全桶对账。临时源从不在发布中删除，由仅匹配临时前缀的一天生命周期清理。

## 配置与本地部署

配置参考 `.env.dev.example` 和 `.env.prod.example`，现有真实 `.env` 不自动修改。

| 配置 | 默认或用途 |
|---|---|
| MEDIA_STORAGE_ENDPOINT | 后端存储端点 |
| MEDIA_STORAGE_UPLOAD_ENDPOINT | 浏览器上传入口根地址，开发为 Caddy |
| MEDIA_PUBLIC_BASE_URL | 桶根的公开读取地址，MinIO包含桶名 |
| MEDIA_KEY_PREFIX | 隔离项目与环境，例如 easy-community/dev |
| MEDIA_COS_CALLBACK_URL / MEDIA_COS_CALLBACK_KEY | 固定生产回调地址与约定密钥 |
| MEDIA_MODERATION_BIZ_TYPE | 生产真实审核策略 |
| MEDIA_WEBHOOK_TOKEN | 开发专用单段通知密钥 |
| MEDIA_UPLOADS_PER_MINUTE | 默认每用户每分钟30次，所有会话共享 |
| MEDIA_POLICY_TTL / MEDIA_NOTIFICATION_TTL / MEDIA_ISSUED_TTL | 5m / 15m / 2h |
| MEDIA_UNREFERENCED_TTL / MEDIA_DELETE_RETRY_DELAY | 24h / 5m |
| MEDIA_PROCESSING_BUDGET | 默认4500ms，不允许高于4500ms |

签发限流使用 Redis MULTI/EXEC 原子更新计数与 TTL，超限429并返回 Retry-After；依赖失败不放行。这不是用户总量或审核费用配额，批量长期消耗仍是已知限制。

开发使用固定 MinIO `RELEASE.2025-09-07T16-13-09Z` 和 Caddy `2.10.2-alpine`：

```powershell
# 在backend目录执行；先准备新的V2空数据库并填写.env.dev。
docker compose --env-file .env.dev -f examples/media/compose.dev.yaml up -d
```

示例使用独立卷和 localhost:19000（MinIO）、19001（控制台）、19002（上传代理），不会复用原9000端口存储。浏览器由授权取得 `http://127.0.0.1:19002/<bucket>`；后端 Copy 使用19000；公开 URL 使用19000加桶名。MinIO通知宿主机8080，容器内不能用自身localhost指代后端；后端 SERVER_ADDRESS 需允许该连接。更改后端端口时同步改 compose 中通知 URL。

创建专用桶，套用填好桶名与前缀的 minio-public-policy.json，仅公开 public/ 读取；temp/ 保持私有。添加仅临时前缀、Expire Days=1 的生命周期。通过 S3 管理 API应用 minio-notification.json，只订阅临时前缀的 s3:ObjectCreated:Post，不能用整个 ObjectCreated 通配事件。FilterRules.Name 必须为小写 prefix。运行期应用不创建桶、不修改桶策略或通知。

Webhook使用 `Authorization: Bearer <token>`。请求上限1 MiB、最多10条Records，共享预算并逐条记录结果；整批期限耗尽后停止剩余发布并记录数量，业务发布失败或超时仍确认接收，不依赖MinIO重投恢复。READY、DELETING、过期或不存在的记录不复制；并发重复通知可能重复复制，不承诺开发恰好一次。

Caddy只透传固定桶POST与必要OPTIONS，以 MinIO HTTP200、ETag和请求ID确认接收成功并输出统一JSON。错误保留失败状态和请求ID，连接失败同样失败；不查询数据库或等待Webhook。代理不处理公开GET，也不缓存尚未发布对象的404。CORS白名单为 MEDIA_FRONTEND_ORIGIN。

生产必须使用COS、HTTPS、约定密钥和真实审核策略，否则拒绝启动。桶未启用版本控制，临时源通过签入Policy的禁止覆盖字段保护。应用身份只具有所需前缀的上传、审核、复制、删除权限，匿名仅能读取公开前缀；使用 cos-cors.xml 配置实际前端来源的POST CORS。

## 验证与切换

```powershell
mvn clean verify
node --test examples/media/upload.test.mjs
```

普通测试不连接外部依赖。V2真实集成测试只有显式 `IT_MEDIA_V2=true` 才运行，启用后所有必填配置缺失直接失败。准备新的 `easy_community_media_v2_test` 或带下划线后缀的空库，设置 IT_MEDIA_DB_URL、IT_DB_USERNAME、IT_DB_PASSWORD、IT_REDIS_*。再设置 IT_MEDIA_BUCKET（本次专属 ec-v2-it-* 桶）、IT_MINIO_ENDPOINT、IT_MINIO_UPLOAD_ENDPOINT、IT_MINIO_ACCESS_KEY、IT_MINIO_SECRET_KEY。

真实测试启动后端18080；专用MinIO预配MEDIA通知目标到该端口、token为测试专用 media-v2-test-token，Caddy桶名与IT_MEDIA_BUCKET相同。测试自行建立并清理专属桶和本次用户，不清空共享库或Redis。测试中等待Webhook仅用于观察结果，不属于产品前端协议。

Windows Java25若出现 `Unable to establish loopback connection`，按本机已存在的短路径设置 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=E:/temp` 后运行测试，不将此运行参数写入生产配置。

可设置 IT_COS_API_URL、IT_COS_USERNAME、IT_COS_PASSWORD、IT_COS_SMALL_IMAGE（PNG）和 IT_COS_LARGE_IMAGE（恰好10MiB的JPEG），运行 `node examples/media/verify-cos.mjs`。使用专用测试账号；脚本不绑定头像，成功上传的无引用图片交给正常GC。脚本不会把本地SDK响应替身视为真实云端。

真实COS验收必须在已开通Callback并设置ckey、真实审核策略和可达回调地址的环境执行：验证POST约束、防覆盖、成功JSON转发、真实审核拒绝/复核、10MiB大图、回调504到上传端203以及协作式超时丢弃语义。离线SDK响应测试或MinIO通过都不能替代生产验收。

部署只使用新库，停止旧实例后统一切换应用、数据库和存储配置。不对旧Flyway历史自动repair/clean，不删除旧桶。回滚按旧应用、旧库和旧配置整体恢复。
