# 文件服务 V1：配置、接口与业务接入

[后端架构](backend-architecture.md) · [启动说明](../../backend/README.md)

本文描述当前 V1 实现。采用 COS 上传回调、同步审核及 MinIO 本地上传网关的后续方案见[文件服务 V2 设计](media-service-v2-design.md)，该设计尚未实现。

## 能力与限制

后端负责签名、HEAD元数据确认、引用和回收。浏览器直接PUT到MinIO/COS；本版不读取文件正文，不检测真实格式、尺寸、动画或内容哈希。`FileContentValidator`只是预留接口，没有实现或默认通过逻辑。声明类型仅允许`image/jpeg`、`image/png`、`image/webp`；头像、封面、正文图片的声明大小上限分别是2、5、10 MiB。HEAD复核不能替代存储入口的大小限制。

上传授权5分钟有效；从申请起24小时内允许确认和首次持久化。存储临时对象配置1天生命周期，按每个对象的年龄异步删除，不是每天清空整个前缀。已持久化文件不受这24小时限制。临时对象无远程预览，前端保留本地File/blob，释放组件时调用`URL.revokeObjectURL`。

## 配置环境

将`.env.dev.example`或`.env.prod.example`中的`MEDIA_*`字段补入对应的真实环境文件。文件使用Java Properties语法，系统环境变量优先。启动会拒绝缺失字段；生产上传和公开域名必须HTTPS。

| 字段 | 含义 |
|---|---|
| `MEDIA_STORAGE_PROVIDER` | `minio`或`cos` |
| `MEDIA_STORAGE_ENDPOINT` | 后端端点；MinIO为服务根地址，COS为完整桶域名 |
| `MEDIA_STORAGE_UPLOAD_ENDPOINT` | 浏览器可达的上传端点，不可在签名后替换Host |
| `MEDIA_STORAGE_REGION` | MinIO通常`us-east-1`；COS填写实际地域 |
| `MEDIA_STORAGE_BUCKET` | 专用桶；COS必须含APPID后缀 |
| `MEDIA_STORAGE_ACCESS_KEY` / `MEDIA_STORAGE_SECRET_KEY` | 专用应用凭证；COS对应SecretId/SecretKey |
| `MEDIA_KEY_PREFIX` | 如`easy-community/dev`或`easy-community/prod`，必须以当前环境名结尾 |
| `MEDIA_PUBLIC_BASE_URL` | 指向桶根的公开域名；MinIO路径式地址包含桶名 |

对象路径为`{prefix}/temp/{UUID}`和`{prefix}/public/{UUID}.{声明扩展名}`。公开地址是公开根地址加完整持久Key。生产图片域名使用独立来源。记录保存提供商、桶和Key；切换提供商、桶或项目前缀不会自动迁移旧对象，旧记录不匹配时返回503，必须先实施独立迁移。仅调整公开根域名不改变对象身份。

## 手动配置MinIO

1. 使用管理员工具创建专用桶，不启用版本控制；应用自身不创建桶或修改策略。
2. 使用专用应用凭证。授权项目临时和公开前缀的`s3:GetObject/s3:PutObject`，只允许公开前缀的`s3:DeleteObject`。给后端专用桶的`s3:ListBucket`权限，以便不存在对象的HEAD能返回404；浏览器只获得指定方法和Key的签名，不获得凭证或列举授权。
3. 复制并替换[公开读取策略模板](../../backend/examples/media/minio-public-policy.json)中的`BUCKET_NAME/PROJECT_PREFIX`，应用到专用桶。桶保持默认私有，仅公开前缀允许匿名GetObject，不使用整桶公开命令。
4. 给**完整项目临时前缀**（例如`easy-community/dev/temp/`）设置过期删除1天。不要匹配整个项目、整个桶或`public/`。
5. 本项目验收的社区版MinIO不支持桶级`PutBucketCors`。在MinIO服务进程/容器中设置`MINIO_API_CORS_ALLOW_ORIGIN=http://127.0.0.1:5173,https://community.example.com`并按该部署方式重新加载。该设置属于存储服务器，不是后端`.env`参数；不要使用默认`*`作为生产白名单。支持桶级CORS的其他发行版可改用桶级规则。
6. 反向代理透传`If-None-Match`、`Content-Type`和签名查询参数，浏览器上传端点指向API端口而不是控制台端口。

临时生命周期可用管理员CLI配置，参数中的桶和前缀必须先核对：

```powershell
# local是部署者已经配置好的管理员mc别名；只添加当前项目临时前缀规则。
mc ilm rule add --id ec-temp-one-day --expire-days 1 --prefix easy-community/dev/temp/ local/easy-community-dev
```

参考：[MinIO环境配置](https://github.com/minio/minio/blob/master/docs/config/README.md)、[条件写入](https://docs.min.io/aistor/developers/s3-api-compatibility/)。

## 手动配置COS

1. 创建从未启用版本控制的专用桶。COS启用版本控制后，`x-cos-forbid-overwrite: true`不再具备禁止覆盖效果。
2. 桶设为私有。替换[公开读取策略模板](../../backend/examples/media/cos-public-policy.json)中的`REGION/APPID/BUCKET_NAME/PROJECT_PREFIX`，仅允许公开前缀匿名读取。
3. 给专用应用CAM身份授权项目对象的`cos:PutObject/cos:HeadObject/cos:GetObject`及公开前缀的`cos:DeleteObject`。不要赋予对象ACL修改、桶配置、删除桶等管理权限。复制需要源GetObject和目标PutObject权限。
4. 为项目临时前缀设置当前版本对象过期`Days=1`，持久前缀不设年龄删除。实际删除为异步任务，不保证第24小时整完成。
5. CORS只允许实际前端来源，方法PUT、GET、HEAD，请求头`Content-Type/x-cos-forbid-overwrite`，可暴露`ETag`；模板见[COS CORS](../../backend/examples/media/cos-cors.xml)。API本身仍沿用同源Cookie和CSRF，不放开后端跨域。
6. 按COS[严格签名配置](https://cloud.tencent.com/document/product/436/83234)启用适用于预签名上传的安全规则，防止额外未签名授权头改变对象访问权限。不要直接套用与预签名上传不兼容的加强写入规则。
7. 生产公开根地址指向配置好的图片自定义域名。部分新COS桶默认域名不支持浏览器直接预览，需要自定义域名。[COS预签名说明](https://cloud.tencent.com/document/product/436/35217)

## 接口和前端流程

| 接口 | 响应 |
|---|---|
| `POST /api/media/uploads` | 201，`mediaId/upload/expiresAt` |
| `POST /api/media/{id}/complete` | 200，状态和声明元数据 |
| `GET /api/media/{id}` | 200，上传者可见状态，不含临时Key或签名读取地址 |
| `PUT /api/users/me/avatar` | 200，实时用户资料 |
| `DELETE /api/users/me/avatar` | 204，可重复调用 |
| `GET /api/users/me` | 实时资料，原有字段加`avatarMediaId/avatarUrl`，无头像时为null |

上传申请正文是`purpose/filename/contentType/size`。响应的`upload`包含`method=PUT/url/headers/expiresAt`。5分钟上传期限在`upload.expiresAt`，24小时业务期限在外层`expiresAt`。文件状态使用`UPLOADED`，不宣称内容验证通过。登录响应及Session身份结构保持不变。

```javascript
// csrf来自当前登录后的/api/auth/csrf；file由文件选择器提供。
const grantResponse = await fetch('/api/media/uploads', {
  method: 'POST', credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
  body: JSON.stringify({ purpose: 'AVATAR', filename: file.name, contentType: file.type, size: file.size })
});
if (!grantResponse.ok) throw new Error('申请上传失败');
const grant = await grantResponse.json();
// 直接发送File，不使用FormData，不把社区Cookie发送给对象存储。
const uploaded = await fetch(grant.upload.url, {
  method: grant.upload.method, headers: grant.upload.headers, body: file, credentials: 'omit'
});
if (!uploaded.ok) throw new Error('上传失败或结果未知，请查询并确认上传状态');
const completed = await fetch(`/api/media/${grant.mediaId}/complete`, {
  method: 'POST', headers: { [csrf.headerName]: csrf.token }, credentials: 'same-origin'
});
if (!completed.ok) throw new Error('确认上传失败');
const profile = await fetch('/api/users/me/avatar', {
  method: 'PUT', credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
  body: JSON.stringify({ mediaId: grant.mediaId })
});
if (!profile.ok) throw new Error('设置头像失败');
```

一次性语义是“首次成功创建后不能覆盖”，不是“发起一次请求即消耗”。MinIO重复创建通常412，COS为409/FileAlreadyExists；移除或修改签名必需头会被拒绝。网络超时或明确对象已存在时，应调用complete核实；不能把任意409或其他存储错误当作成功。相同Key不续签，修改内容需要重新申请。5分钟后新请求无权上传，但已受理请求可能继续完成。

错误沿用ProblemDetail：401登录失效、403权限或CSRF、404不存在或非本人、409状态竞争、410临时过期、413大小超限、422声明元数据不符、503依赖暂不可用。不可用的重试可参考Retry-After，不自动重试业务修改。

## 关联、事务和文章契约

`media_reference`没有出现位置或usage_type：

- `UNIQUE(reference_type,entity_id,media_id)`去重同一实体和文件。
- 生成列`avatar_user_id`仅对USER_AVATAR返回entity_id，唯一索引保证一个用户最多一个当前头像；ARTICLE行生成NULL。
- 不对media_id设唯一索引；同文件可跨文章引用。

头像复制在数据库事务外完成；用户行先锁定，再按文件ID排序加锁，更新同一头像引用行，并同步users.avatar_media_id。不增加auth_version。用户软删除同事务解除头像。

未来文章先验证操作权限，调用`MediaService.prepareArticle(owner, bodyIds, coverId)`，再开启**显式READ_COMMITTED事务并锁定文章行**，保存业务字段并调用`MediaReferenceService.syncArticle`。引用方法是MANDATORY事务；不能单独提交文件关联，也不能把复制放到业务事务里。

关联目标是`Set(正文全部mediaId) ∪ Set(封面mediaId)`。重复正文节点保留在JSON中但只关联一次；正文移除而封面仍使用时保留关联；文章A解绑不影响文章B。允许复用本人的已持久化文章图片，封面实际大小仍需不超过5 MiB。删除保留期由文章模块决定，正式解除引用后才交给文件回收。

## 状态、补偿和运维

状态为ISSUED、UPLOADED、PROMOTING、READY、DELETING、DELETED，另有REJECTED与EXPIRED。READY表示对象准备好，是否占用以引用表为准。复制目标在外部操作前登记，持久化租约2分钟，过期工作者由操作令牌拒绝回写。网络复制超时先检查目标，避免丢失成功结果。

Web服务每5分钟最多处理50个候选记录，非Web管理员初始化不执行回收。持久对象无引用7天后，在READ_COMMITTED事务持有文件行锁并读取最新提交引用，再标记DELETING；事务外删除。后端绑定也持有同一文件锁，所以清理等待期间提交的新引用会阻止删除。失败5秒起指数退避，最长1小时。删除墓碑保留，每天复查并删除迟到对象。临时源不提前删除，交给存储生命周期。

监控文件维护日志中的mediaId和错误类型，数据库按status/failure_code/retry_count检查失败堆积；日志不得记录签名URL、表单、凭证或SDK错误正文。公开对象缓存1小时，删除后客户端或CDN可能继续使用已缓存内容至过期。

## 测试与部署验收

先创建专用`easy_community_auth_test`和`easy_community_media_test`库。提供`IT_DB_PASSWORD`和Redis连接参数后运行`mvn test`；文件测试可覆盖`IT_MEDIA_DB_URL`，但库名必须是`easy_community_media_test`。测试不会清空Redis或业务桶。

真实MinIO测试设置`IT_MINIO_ENDPOINT/IT_MINIO_ACCESS_KEY/IT_MINIO_SECRET_KEY`。测试自行创建`ec-media-it-随机值`桶，配置测试策略和生命周期，结束时仅清理本次桶；凭证需要这些测试管理权限，不能直接复用生产最小权限身份。

真实COS测试设置`IT_COS_ENDPOINT/IT_COS_REGION/IT_COS_BUCKET/IT_COS_ACCESS_KEY/IT_COS_SECRET_KEY/IT_COS_PUBLIC_BASE_URL`，可选`IT_MEDIA_ORIGIN`。桶名必须以`ec-media-it-`开头，预先按`ec/test`配置临时生命周期、公开读取及CORS。测试不改桶规则，只清理本次已知Key。未提供COS变量时明确跳过，不能视为COS线上验收通过。

```powershell
# 从安全配置中注入上述测试变量后运行；Windows套接字问题使用已存在的普通临时目录。
mvn '-DargLine=-Djdk.net.unixdomain.tmpdir=E:/temp' clean verify
```

部署时检查：临时对象匿名403；持久对象可读；条件头删除或篡改不能上传；重复及并发上传不覆盖；5分钟后拒绝新上传；浏览器预检可通过；24小时后后端拒绝未提交文件；生命周期仅匹配临时前缀。SDK签名离线测试不能代替真实桶及浏览器网络验收。
