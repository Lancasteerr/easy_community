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

每批独立验证并小步提交，保留无关未跟踪文档。新增SQL从V5开始，不改旧迁移。功能默认关闭，先初始化基础设施与纪元，再开消费者、查询和写入口。

## 实际实现与默认配置

SQL迁移为`V5__create_article_like.sql`，会为公开和隐藏的已有文章初始化零计数。新建文章无论功能是否启用，均在同一事务建立计数。取消和账号注销不清关系墓碑；没有在线物理删除或自动修数入口。

功能关闭时保留原有文章响应。`LIKE_ENABLED=true`后，详情和作者列表在共享内容取出后追加`likeCount`和`likedByMe`，游客恒为false，编辑接口不追加。正常点赞查询最多两条批量回源SQL；认证已有的SQL校验不计入该数量。

| 配置 | 默认值 / 说明 |
|---|---|
| LIKE_ENABLED | false，模块总开关 |
| LIKE_WRITE_ENABLED | false，写入口开关，关闭后消费者可继续运行 |
| LIKE_CONSUMER_ENABLED | true，仅模块启用且非维护进程时生效 |
| LIKE_PER_MINUTE | 每用户每分钟60次，仍叠加现有认证入口限流 |
| LIKE_CONFIRM_TIMEOUT | 2s，只是确认等待预算，不是完整HTTP延迟承诺 |
| app.like.pending-limit | 128 / 实例，超限在发送前拒绝 |
| app.like.concurrency / prefetch | 2 / 20；两个应用实例共有4个消费者 |
| app.like.queue-bytes | 工作队列和DLQ各64MiB；脚本QueueBytes需保持一致 |
| app.like.read-concurrency / read-timeout | 每实例4个在途回源任务 / 300ms等待预算 |
| MANAGEMENT_PORT | 18081，本机管理端口；第二实例必须改成其他端口 |

上述`app.like.*`均可通过Spring命令行属性覆盖。普通应用重启不初始化分配器或提升纪元。分配器Key无TTL，展示缓存单独位于`<namespace>:cache:v1:*`，不要通过修改namespace来清理缓存。

## 首次部署

1. 保持写入口关闭，部署迁移和代码。将`.env.dev.example`或`.env.prod.example`中的RabbitMQ配置填入实际环境，Redis必须使用noeviction。
2. 在PowerShell进程环境中设置RabbitMQ管理凭证和vhost，执行：

```powershell
./infra/dev/like-topology.ps1 -Namespace ec:dev:like -ManagementUrl http://127.0.0.1:15672
./infra/dev/like-topology.ps1 -Namespace ec:dev:like -ManagementUrl http://127.0.0.1:15672 -VerifyOnly
```

脚本只声明本命名空间拓扑，不删除消息；核验Quorum类型、持久性、安全死信参数、无过期策略及绑定。应用也使用相同显式队列参数声明拓扑，已有不兼容队列不能静默复用。

3. 在backend目录构建后，使用非Web维护进程初始化纪元。以下首次启动预期SQL epoch为0，后续必须填写status输出中的实际值：

```powershell
mvn -DskipTests package
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.like.enabled=true --app.like.write-enabled=false --app.like.maintenance=recover --app.like.expected-epoch=0
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.like.enabled=true --app.like.write-enabled=false --app.like.maintenance=status
```

4. 核验成功后，先以模块启用、写关闭启动消费者和查询，再启用写入口。管理健康和指标只允许本机管理端口GET访问`/actuator/health`、`/actuator/prometheus`，业务端口不开放这两个接口。

## 故障恢复与维护

- 所有维护动作必须使用上述非Web模式并显式关闭写入口。维护进程不启动监听器及业务定时任务。失败退出码非零；status未就绪也非零，不因命令打印了状态就开放写入。
- Redis重启、进程标识变化、任一分配器Key丢失、TTL异常或序号耗尽时停止签发。恢复前关闭外部点赞写路由，停掉所有旧写实例；保留写关闭的应用实例消费已有消息。
- `--app.like.maintenance=pause`只暂停签发，不清队列或状态。恢复使用MySQL专用连接命名锁；先持久提升纪元，再初始化PAUSED分配器，最后条件激活READY。
- MySQL已提交但Redis初始化失败时保留纪元空洞，重试必须再提升纪元，不能在已保留纪元内归零。旧恢复进程不能覆盖更高纪元或激活较旧纪元。
- 若更换Redis地址，先隔离旧端点；不支持透明自动切主。恢复结束先核验status，再恢复写配置和外部路由。
- 工作队列失败消息最多尝试5个独立SQL事务，退避1/2/4/8秒；耗尽或遇到格式、版本冲突、计数异常时由Broker可靠转入DLQ。
- 当前固定镜像的RabbitMQ 4.4.0内部死信发布重试默认180秒，修复DLQ后可能等待该周期。专用测试Broker使用1秒周期，不更改生产默认配置。
- 告警建议：DLQ大于0、分配器拒签、计数不变量错误立即处理；积压持续增加或发布拒绝时关闭/降低入口，不能靠增大队列无限承接SQL故障。

重放与对账示例：

```powershell
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.like.enabled=true --app.like.write-enabled=false --app.like.maintenance=replay --app.like.replay-limit=100
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.like.enabled=true --app.like.write-enabled=false --app.like.maintenance=audit
```

replay每次最多10000条，保留原body、版本与messageId；确认重新入队后才ACK原死信。未知结果保留原死信，重复由SQL版本裁决。格式已损坏的消息停止本次重放，不能盲目重新编号绕过隔离。

audit在主库一致性快照中报告关系聚合和计数差异，不执行修数；没有文章反向索引，因此不安排周期性全表扫描。回退时先关写，继续消费已受理命令；不回滚迁移、清空队列、删除墓碑或重置纪元。

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

已完成的双实例实验与部署建议见[容量报告](like-capacity-2026-11-05.md)。正式环境仍须在目标硬件上重复验证，保持写开关关闭直到验收完成。

本次测试记录及既有媒体测试的排除说明见[验证记录](like-validation-2026-11-06.md)，不能将带排除项的构建称为无条件全量通过。
