# 文章 CRUD：最终契约与恢复

> Windows 命令中的 `JAVA_SOCKET_TMPDIR` 需由运行者预先设置为本机可用的短ASCII绝对路径，并确保目录已创建；其他平台可省略该JVM参数。此环境变量仅用于展开本文命令，不是应用配置。

## 范围

同步保存、不可恢复隐藏、详情、作者编辑读取、本人公开文章管理、最小 Tag 目录及文章双投影缓存。不包含前端、公共信息流、计数、搜索、消息队列、修订或请求幂等。隐藏保留文件引用，不物理删除图片。

代码先按文章能力分包：`article.content` 处理正文读写及缓存，`article.tag` 管理标签目录，`article.view` 管理浏览统计，`article.like` 管理点赞；各能力内部再分 web、application 和 infrastructure。完整结构与共享事务边界见[后端架构约定](../../../architecture/backend/zh-CN/backend-architecture.md)。Controller 不直接访问数据库或 Redis。作者显示资料经用户应用层读取，文件引用复用媒体应用服务；不创建空 domain 包或 Service/ServiceImpl。

## HTTP

| 接口 | 语义 |
|---|---|
| POST /api/articles | articleId 缺失/null 新增，正 BIGINT 字符串修改；200 + articleId |
| DELETE /api/articles/{id} | 作者、ADMIN、PATROLLER 可隐藏；204，授权成立的重复删除仍成功 |
| GET /api/articles/{id} | 游客可读，隐藏/不存在 404 |
| GET /api/articles/{id}/edit | 仅作者，主库直读，隐藏 409 |
| GET /api/users/me/articles | 仅本人公开文章，page=1、size=20，最大50，ID倒序 |
| GET /api/tags | 登录后读取目录 |
| POST /api/admin/tags | 管理员新增，201 |

所有响应 no-store，文章、作者和 Tag ID 为字符串。编辑权不因管理员/巡查员角色扩展。保存为完整替换；非法 ID 不回退新增，不存在不 UPSERT。

保存体字段仅限 articleId、title、content、coverMediaId、tagIds。除 articleId 外必须显式提交，coverMediaId=null 清除封面，tagIds=[] 清除关联。content={schemaVersion:1,doc:受限Tiptap文档}。摘要从正文生成，不接收 HTML、身份、状态、时间或独立摘要。

标题1～100个Unicode码点，摘要合并空白后取前200码点；纯图片允许，空白正文拒绝。请求最多1MiB，深度32、节点10000、图片节点50、Tag5。Tag名去首尾空白，1～32码点，区分大小写唯一，不支持改名/删除/排序。

正文节点为 doc、paragraph、text、heading(1～6)、hardBreak、horizontalRule、blockquote、bulletList、orderedList、listItem、codeBlock、image。标记为 bold、italic、strike、underline、code、link；验证结构，拒绝未知节点/标记。图片 attrs.fileId 为标准小写UUID，src由服务器重建；任意CSS/事件属性不进入保存内容。链接仅绝对HTTP/HTTPS，HTML转义，代码块不做服务端高亮。

## SQL 与错误

Flyway V3新增 article、tag、article_tag，不重写历史迁移。JSON保存schemaVersion/doc，SQL仅存封面ID。主键AUTO，作者管理索引(author_id,hidden,id)。创建时间和发布时间为同一创建事务时间，编辑只改更新时间。

无外层事务的保存服务先准备内容，再调用独立代理的RC写服务：创建INSERT取得ID；修改/隐藏先锁文章行，再锁内验权限和状态。保存调用现有syncArticle维护文件引用，内容/封面/Tag/引用同事务。隐藏只改状态。无版本、CAS、SQL自动重试或自动核对。

提交已确认后才维护缓存。明确回滚结束请求；提交通信故障不确定时返回503 ARTICLE_WRITE_RESULT_UNKNOWN，提示正常查询核对，不重发。确认SQL成功而Redis失败仍返回成功。无变化修改不依赖影响行数判断冲突。

详情、编辑、列表DTO在短只读RR事务中装配，写事务RC。作者显示信息只批量读ID/username，软删除账号不影响历史署名。列表不读取HTML/JSON，详情和列表均保存coverUrl、作者名和Tag名。

作者管理列表只选本人且hidden=false；并发隐藏导致缓存冲突时批量核对主库，确认隐藏才省略，不补页。total为分页查询快照数量，允许并发删除造成短页。不存在隐藏条目私有装配或hidden响应字段。

## 缓存与恢复

文章前缀包含环境、版本；每篇文章detail/list/hidden/cache-lock使用同一hash tag。正缓存270～300秒，负缓存20秒，命中不续期；hidden无自动TTL。共享锁5秒不续租，争锁等待累计最多300ms，单次命令沿用Redis2秒超时，不承诺300ms请求总时限。

Lua在同一原子动作内核token后发布、双负缓存、双失效、双隐藏或解锁；正缓存发布还查hidden。SQL回源在获得锁后开始RR事务。批量锁非阻塞，处理并释放本轮锁后才等待竞争项；合并SQL，逐项核token，恢复输入顺序。提交后至多尝试3次维护，不回写旧payload，不建立重试队列。

Redis故障或争锁超限，读返回503；写入已确认则仍成功。返回前复查hidden，但不撤回在途响应。失效未执行时旧展示可持续到剩余TTL及有效在途回填预算。

恢复必须停流量及旧实例，选择新的文章缓存版本，从SQL按ID分页重建隐藏墓碑，再启新实例；不得FLUSHDB，不恢复旧版本前缀。

先按后端README加载当前环境的数据库、Redis、媒体配置，再运行下列离线命令（示例为开发环境，生产使用prod及对应配置）：

```powershell
$env:ARTICLE_CACHE_VERSION = 'recovery_20261101_1'
java "-Djdk.net.unixdomain.tmpdir=$env:JAVA_SOCKET_TMPDIR" -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.article.cache-rebuild.enabled=true
```

命令成功后自动退出，仅新文章命名空间写入墓碑与完成标记；已有Key的版本会被拒绝。失败时保持停流量，排除故障后选择另一个全新版本重建。全部Web实例使用同一个ARTICLE_CACHE_VERSION正常启动，**不携带cache-rebuild.enabled参数**，再恢复流量。下次恢复不得复用本次版本；Redis自身恢复还须遵循现有Session服务要求。

GET响应附带服务端生成的X-Request-Id，写入日志包含操作、articleId、准备/事务耗时和关联ID。缓存日志分别记录详情三态、列表回源数量、token丢失及维护失败；不记录正文或凭证。

## 分批验证

> 以下分批顺序保留原实施背景，不是当前待办；验证命令须结合当前版本和专用环境使用。

分别提交文档、模型/Tag、渲染、事务、HTTP/查询、缓存原子操作、缓存接入/恢复。测试随功能加入，每批mvn test，最终mvn clean verify。真实MySQL/Redis测试显式启用，仅使用专用库及随机前缀；跳过不算通过。用屏障控制并发，验证事务代理、双写覆盖、双向删写、GC、锁过期、提交结果未知及缓存失败隔离。

在backend目录运行；凭证通过环境变量提供，不写入命令或提交：

```powershell
# IT_DB_URL必须指向easy_community_*test*专用库，IT_DB_PASSWORD必须显式提供。
$env:IT_ARTICLE = 'true'
$env:IT_REDIS = 'true'
$env:IT_DB_URL = 'jdbc:mysql://127.0.0.1:3306/easy_community_article_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
mvn "-DargLine=-Djdk.net.unixdomain.tmpdir=$env:JAVA_SOCKET_TMPDIR" '-Dtest=Article*Tests' test
```

IT_DB_USERNAME、IT_DB_PASSWORD及IT_REDIS_HOST/PORT/PASSWORD沿用后端测试约定。普通mvn test不启用外部服务；最终启用这些变量执行mvn clean verify时，也会执行既有认证/持久层集成测试。Windows临时套接字参数仅为本地测试使用，其他平台可省略。

## 本次验收记录（2026-11-01）

> 以下为2026-11-01的历史验收，保留当时结果与范围，不代表当前版本已重新运行或通过。

在专用easy_community_article_test库及真实Redis上，启用IT_ARTICLE、IT_REDIS与数据库凭证后运行 `mvn "-DargLine=-Djdk.net.unixdomain.tmpdir=$env:JAVA_SOCKET_TMPDIR" clean verify`，构建成功：142项通过、0失败、0错误；文章模块39项全部通过。另有10项既有MediaV2IntegrationTests因未启用真实对象存储联调环境而跳过，未计入通过数量。

已实测安全正文/摘要/输入边界、HTTP/Session/CSRF/角色、不可恢复隐藏及公开管理列表、真实事务回滚/自增回填/媒体关联、完整修改覆盖、双向删写、一致读取视图、真实MySQL锁等待超时及死锁、双投影Lua与旧token拒绝、批量装配/负缓存核对、提交后缓存异常隔离，以及新命名空间恢复规则。

COMMIT故障通过测试专用JDBC代理分别在真实提交前、真实提交后丢失确认，两种情况均返回ARTICLE_WRITE_RESULT_UNKNOWN，验证不自动查结果或重放。崩溃测试启动独立JVM，代理提交成功后立即halt，确认SQL已更新而两种缓存仍旧；将该次测试Key剩余TTL缩短为50ms并等待实际过期后，详情和列表均回源新内容。生产270～300秒TTL范围与命中不续期另由真实Redis测试断言。
