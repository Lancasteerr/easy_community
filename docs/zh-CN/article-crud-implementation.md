# 文章 CRUD：最终契约与恢复

## 范围

同步保存、不可恢复隐藏、详情、作者编辑读取、本人公开文章管理、最小 Tag 目录及文章双投影缓存。不包含前端、公共信息流、计数、搜索、消息队列、修订或请求幂等。隐藏保留文件引用，不物理删除图片。

代码沿用按业务分包：article.web 处理 HTTP，article.application 组织用例和事务，article.infrastructure 管理持久化、配置与缓存。Controller 不直接访问数据库或 Redis。作者显示资料经用户应用层读取，文件引用复用媒体应用服务；不创建空 domain 包或 Service/ServiceImpl。

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

恢复必须停流量及旧实例，选择新的文章缓存版本，从SQL按ID分页重建隐藏墓碑，再启新实例；不得FLUSHDB，不恢复旧版本前缀。具体命令随恢复入口交付。

## 分批验证

分别提交文档、模型/Tag、渲染、事务、HTTP/查询、缓存原子操作、缓存接入/恢复。测试随功能加入，每批mvn test，最终mvn clean verify。真实MySQL/Redis测试显式启用，仅使用专用库及随机前缀；跳过不算通过。用屏障控制并发，验证事务代理、双写覆盖、双向删写、GC、锁过期、提交结果未知及缓存失败隔离。
