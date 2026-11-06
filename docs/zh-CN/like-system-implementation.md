# 文章点赞实施与运维协议

## 已确认边界

- 后端闭环，文章详情和作者列表追加 `likeCount`、`likedByMe`；不修改其他工作区前端，不新增公开批量查询。
- 每关系一行状态和最高版本，取消保留墓碑；关系与精确计数同事务。没有历史表、Outbox、计数分片和HTTP请求去重。
- 单节点依赖，验证两个应用实例；故障停写后人工恢复纪元，已有消息继续消费。
- Redis只是展示缓存；前端乐观计数，Redis不做INCR。状态固定600秒、计数固定30秒，不滑动续期。
- 点赞缓存失败回源SQL；没有可靠数据时整个接口503，不能伪造零或未点赞。

## 接口约定

`PUT /api/articles/{articleId}/like`设置点赞，`DELETE`设置取消。必须携带UUID格式的`X-Operation-Id`，沿用Session/CSRF。每次HTTP调用都会重新分配版本，operationId只作追踪。

202响应为`{accepted:true,operationId,liked,version}`，version是十进制字符串。只有路由成功且Broker确认后返回；liked是受理目标，不是SQL提交证明。旧响应不得覆盖前端新目标；超时结果未知，不无限重发旧意图。

| 场景 | HTTP / code |
|---|---|
| 参数或UUID非法 | 400 / INVALID_LIKE_REQUEST |
| 文章不存在或隐藏 | 404 / ARTICLE_NOT_FOUND |
| 频率超限 | 429 / RATE_LIMITED，附Retry-After |
| 分配器暂停、缺Key或运行标识变化 | 503 / LIKE_ALLOCATOR_UNAVAILABLE |
| 明确未开始发布 | 503 / LIKE_PUBLISH_UNAVAILABLE |
| 无路由 | 503 / LIKE_PUBLISH_REJECTED |
| 发布后无法确认 | 503 / LIKE_WRITE_RESULT_UNKNOWN |

## 实施批次

1. 协议与文档；2. SQL迁移和消费事务；3.可靠发布；4.消费、死信及重放；5.分配器和恢复；6.批量缓存查询；7.API与文章响应；8.集成故障测试、指标、容量报告。

每批独立验证并小步提交，保留无关未跟踪文档。新增SQL从V5开始，不改旧迁移。点赞固定集成，正常Web应用默认提供查询、写入并消费MQ；上线时由运维关闭外部入口，完成依赖与纪元准备后再开放。

## 实际实现与默认配置

SQL迁移为`V5__create_article_like.sql`，会为公开和隐藏的已有文章初始化零计数。新建文章在同一事务建立计数。取消和账号注销不清关系墓碑；没有在线物理删除或自动修数入口。

详情和作者列表始终在共享内容取出后追加`likeCount`和`likedByMe`，游客恒为false，编辑接口不追加。正常点赞查询最多两条批量回源SQL；认证已有的SQL校验不计入该数量。

| 配置 | 默认值 / 说明 |
|---|---|
| LIKE_PER_MINUTE | 每用户每分钟60次，仍叠加现有认证入口限流 |
| LIKE_CONFIRM_TIMEOUT | 2s，只是确认等待预算，不是完整HTTP延迟承诺 |
| app.like.pending-limit | 128 / 实例，超限在发送前拒绝 |
| app.like.concurrency / prefetch | 2 / 20；两个应用实例共有4个消费者 |
| app.like.queue-bytes | 工作队列和DLQ各64MiB；脚本QueueBytes需保持一致 |
| app.like.read-concurrency / read-timeout | 每实例4个在途回源任务 / 300ms等待预算 |
| MANAGEMENT_PORT | 18081，本机管理端口；第二实例必须改成其他端口 |

上述`app.like.*`均可通过Spring命令行属性覆盖。普通应用重启不初始化分配器或提升纪元。分配器Key无TTL，展示缓存单独位于`<namespace>:cache:v1:*`，不要通过修改namespace来清理缓存。

## 部署、重启和故障运维

完整操作步骤统一见[点赞运维手册](like-system-operations.md)，包括两个.env示例、AMQP与管理端口、首次初始化、普通应用重启、Redis/MySQL/RabbitMQ宕机、整机恢复、死信及数据回滚边界。

正常Web应用自动提供点赞能力，不再读取旧模块、写入或消费开关。关闭外部端口时，后台消费者仍处理已受理命令；需要暂停消费时停止应用。纪元恢复前必须停止全部业务实例，恢复后在外部入口关闭期间完成本机验证，最后人工开放。

普通应用启动不会自动初始化或提升纪元。分配器未就绪时写接口503且健康检查异常，但不停止消费者；不增加自动恢复、自动开网或状态修改HTTP接口。非Web维护命令不启动消费者、自动MQ拓扑或业务定时任务，只有replay显式连接MQ。

## 前端接入

同文章只保留一个在途写请求，点击立即改变本地目标，快速切换合并为最终目标；仅本地布尔状态实际改变时加减显示计数。旧HTTP响应不覆盖后来意图。

operationId是追踪标识，同一个ID再次HTTP调用也会获得新版本。超时意味着未知，不无限重发已被新意图取代的请求；刷新后允许采用服务端暂时落后的展示。没有严格跨故障请求幂等、逐操作查询或读己之写保证。

Redis Pending只是尽力展示；Hash丢失后旧回调可能短暂写回旧状态。固定600秒/30秒是单次缓存寿命，不是包含MQ积压、进程暂停或DLQ修复在内的全链路收敛时限。

## 自动化验证

```powershell
# 仅操作独立测试项目，首次自动创建随机凭证，保存在被Git忽略的backend/.env.like-it。
./infra/test/run-like-tests.ps1
# 显式启用只针对带ec.like.test标签容器的重启/进程崩溃演练。
./infra/test/run-like-tests.ps1 -Faults
# 全量clean verify，同时开启原有文章MySQL/HTTP和Redis回归。
./infra/test/run-like-tests.ps1 -Verify -Faults
# 独立Redis7.2兼容实例：真实执行分配器和缓存Lua，不进行重启实验。
./infra/test/run-like-tests.ps1 -Redis72
# 构建后，以实际JAR验证status/pause/recover/replay/audit和失败退出码。
./infra/test/like-maintenance-smoke.ps1
# 双实例阶梯负载，默认每阶段15秒；可用IT_LIKE_LOAD_SECONDS延长至300秒。
./infra/test/run-like-tests.ps1 -Tests LikeCapacityTests -Load
```

测试库为`easy_community_like_test`，端口17706/17779/17772，管理端口17773；不复用开发容器。Windows测试JVM使用短ASCII临时套接字目录，不更改系统设置。

同一个专用测试项目必须顺序执行，不能同时运行多个测试入口或旧版对照测试。Windows JDK25若本机临时目录导致`UnixDomainSockets Invalid argument`，执行实际JAR时也需在`-jar`前添加`-Djdk.net.unixdomain.tmpdir=E:/temp`（换成已创建的本机短ASCII目录）；测试脚本已处理该参数。

Redis7.2兼容实例使用17778端口；7.2和7.4均已验证Lua INFO、运行栅栏及19位字符串比较。分配器恢复用户需要INFO和CONFIG GET权限，应用用户至少需要脚本使用的INFO及相关Key读写权限；权限不足必须拒签。不要将测试凭证用于开发或生产。结束实验可使用相同Compose文件及`--env-file backend/.env.like-it --profile compatibility down -v`清理本测试项目。

容量JSON输出到`backend/target/like-capacity.json`，记录认证在内的HTTP延迟、响应分类、接受后待提交数量、SQL锁等待及对账结果。每个用户客户端串行，两个应用实例共享Session、签发器和队列。热读预热后测量，冷读从清除本实验展示缓存开始；不会清理签发器。

指标标签只包含固定动作和结果，禁止用用户/文章ID作为标签。Broker队列深度和磁盘、MySQL锁等待使用原生监控；队头时间不可用时明确标记未知。消费者排队年龄是投递采样，不冒充全队列最老消息年龄。

已完成的双实例实验与部署建议见[容量报告](like-capacity-2026-11-05.md)。正式环境仍须在目标硬件上重复验证，保持外部入口关闭直到人工验收完成。

当前测试结果与历史结果区分记录，见[验证记录](like-validation-2026-11-06.md)；本次完整回归不沿用历史媒体测试排除项。
