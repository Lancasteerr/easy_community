# 点赞功能部署、重启与故障处置手册

点赞是项目固定功能。正常Web应用启动即提供点赞读写并消费MQ，不需要启用开关。本文适用于单节点MySQL、Redis、RabbitMQ及多个应用实例，不提供节点级容灾或自动故障恢复。

## 1. 操作原则和入口控制

- 由运维关闭反向代理入口或阻断业务端口，阻止外部新请求；修复、启动和本机验证期间保持关闭，最后人工开放。应用不会管理防火墙，也不会自动开放外部入口。
- 关闭端口不等于停止在途HTTP请求或后台消费者。依赖正常时允许消费者继续排空已受理命令；必须暂停消费时停止全部应用进程。
- **执行纪元恢复前必须停止全部业务实例**，包括仍在运行但已关闭外部端口的实例。存在无法确认结束的在途发布时，停止进程后再恢复。短时暂停消费不会清除持久队列。
- 普通应用启动不创建签发器、不提升纪元、不重置序号。签发器未就绪时写接口返回`503 LIKE_ALLOCATOR_UNAVAILABLE`；健康异常不会自动终止进程或关闭消费者。
- 202只证明Broker可靠受理，不证明SQL已落库。HTTP或发布结果未知时不能认定未执行，不能无限重发；重投同一MQ命令由SQL版本幂等裁决。
- 不清空队列、不删除版本墓碑、不回滚迁移，也不通过修改命名空间重置版本。正常恢复不能将Redis计数写回SQL。

## 2. 环境准备

按运行环境复制`backend/.env.dev.example`或`backend/.env.prod.example`为对应真实文件，填写MySQL、Redis、RabbitMQ和现有文件存储配置。示例是Java Properties格式，无需引号，不支持shell变量展开；真实凭证不提交Git。

RabbitMQ应用连接参数为`RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST`。`PORT`默认5672，是AMQP端口；宿主机映射不同则填写实际映射端口。管理API地址是另一连接地址，默认15672，不能填入应用的AMQP端口。生产使用私网地址和专用账号，目标vhost需事先创建，账号需具有目标命名空间内资源的configure/write/read权限。示例文件和拓扑脚本不会创建账号或vhost。

Redis必须为`noeviction`，持久化目录不能意外更换。运行账户需具备Lua所用的INFO及相关Key权限，恢复账户还需`CONFIG GET`权限。签发器Key无TTL；展示缓存和签发器使用独立Key，不能用FLUSHDB清缓存。

模块原有`LIKE_ENABLED`、`LIKE_WRITE_ENABLED`、`LIKE_CONSUMER_ENABLED`及对应的`app.like.enabled`、`app.like.write-enabled`、`app.like.consumer-enabled`属性已删除，必须从既有部署配置清理；旧的false值不会阻止新版服务提供功能。上线前先关闭外部入口。

每实例管理地址为`127.0.0.1`，默认端口18081；第二实例设置不同`MANAGEMENT_PORT`。只允许本机管理端口读取`/actuator/health`、`/actuator/prometheus`，不开放状态修改管理API。健康检查异常可用于告警，不能把它直接当作必须循环重启进程的依据。

## 3. 维护命令及返回结果

在`backend`目录执行。按实际环境将dev替换为prod；使用当前构建制品，命令不依赖点赞开关。

```powershell
mvn -DskipTests package
$likeJvmArgs = @()
$likeArgs = @('-jar', 'target/backend-0.0.1-SNAPSHOT.jar',
    '--spring.profiles.active=dev', '--spring.main.web-application-type=none')
```

Windows JDK25如遇`UnixDomainSockets Invalid argument`，先创建本机短ASCII目录，再在`-jar`之前增加JVM参数；此设置只作用于本次命令：

```powershell
New-Item -ItemType Directory -Force -Path E:/temp | Out-Null
$likeJvmArgs = @('-Djdk.net.unixdomain.tmpdir=E:/temp')
```

所有维护动作使用非Web进程，不启动监听器、自动MQ拓扑或业务定时任务；动作完成后关闭连接并退出。除显式重放外，状态、暂停、恢复及对账不需要连接RabbitMQ。

| 动作 | 命令 | 成功结果与失败处理 |
|---|---|---|
| 状态 | `java @likeJvmArgs @likeArgs --app.like.maintenance=status` | JSON含sqlEpoch、redisEpoch、sequence、state、ready；READY且匹配才退出0；检查不消耗seq |
| 暂停签发 | `java @likeJvmArgs @likeArgs --app.like.maintenance=pause` | `paused:true`且退出0；不会停止消费者或清消息；后续恢复需走新纪元流程 |
| 恢复 | `java @likeJvmArgs @likeArgs --app.like.maintenance=recover --app.like.expected-epoch=$expectedEpoch` | 返回新epoch且退出0；预期SQL纪元错误、锁冲突或其他失败退出非零，保持入口关闭 |
| 重放 | `java @likeJvmArgs @likeArgs --app.like.maintenance=replay --app.like.replay-limit=100` | 返回实际replayed数量，空队列为0；单次1至10000条，仅确认重新入队后ACK原死信 |
| 对账 | `java @likeJvmArgs @likeArgs --app.like.maintenance=audit` | 一致时`consistent:true`；差异或访问失败退出非零，仅报告不修数 |

PowerShell使用`$LASTEXITCODE`检查退出码。启动日志不等于动作成功，status打印了未就绪JSON也不能开放入口。必要时用只读SQL获取预期纪元：

```sql
SELECT epoch FROM like_version_epoch WHERE id=1;
```

将结果填入`$expectedEpoch`，不得猜测。SQL不可用或无法确认结果时停止恢复。错误类型、环境地址、命名空间和命令输出应记录到本次维护记录中，不记录密码。

## 4. 首次部署

触发条件：新环境或从未初始化签发器。服务影响：外部服务保持关闭，初始化完成前点赞写入不可用。需要提升纪元：是，初始SQL纪元0提升至1。

1. 关闭外部入口，保持所有业务实例停止。确认三个依赖的持久目录、账号及网络可用，构建当前后端制品。
2. 执行上述status命令。应用上下文会先执行Flyway迁移；首次签发器未初始化时status非零属于预期。确认V5已建立三张表及全部已有文章的零计数，不修改历史迁移。
3. 由运维声明并核验拓扑。脚本使用进程环境中的RabbitMQ管理凭证，其管理权限可由独立运维账号提供；应用读取的.env文件不会自动导出为PowerShell环境变量。脚本在独立运维终端执行，避免临时管理凭证覆盖后续应用启动使用的连接账号。

```powershell
$env:RABBITMQ_USERNAME = 'ec_dev'
$env:RABBITMQ_PASSWORD = '<实际运维账号密码>'
$env:RABBITMQ_VHOST = 'easy_community_dev'
# 命名空间必须与应用app.like.namespace一致；生产使用实际生产命名空间及管理地址。
./infra/dev/like-topology.ps1 -Namespace ec:dev:like -ManagementUrl http://127.0.0.1:15672
./infra/dev/like-topology.ps1 -Namespace ec:dev:like -ManagementUrl http://127.0.0.1:15672 -VerifyOnly
```

上述脚本从仓库根目录执行；从backend目录执行时路径改为`../infra/dev/like-topology.ps1`。两条命令均应退出0。脚本不删除消息，核验持久Direct Exchange、Quorum队列、绑定、工作队列delivery-limit=20、可靠死信、reject-publish、DLQ无限投递、容量及无过期配置。应用声明参数必须与脚本一致。

4. 读取SQL epoch，设置`$expectedEpoch`，执行recover。初始化PAUSED后条件激活READY，随后status必须退出0。失败不开放入口，也不手动补齐或清零签发Key。
5. 启动业务实例，外部入口继续关闭。正常Web实例自动消费，无需再设启用参数。
6. 完成本节末尾的开放检查后人工开放外部服务。

## 5. 重启与宕机场景

以下场景均先关闭受影响外部入口，按表中步骤处理，并满足第9节开放检查。记录维护前后SQL epoch、Redis运行标识和积压，区分服务重启与持久数据回退。

| 场景与触发条件 | 服务影响 | 是否提升纪元 | 处理顺序 | 验证重点 |
|---|---|---|---|---|
| 单实例正常重启/滚动发布 | 目标实例停止接单，其他实例可继续消费 | 否，前提是Redis运行标识与签发器正常 | 摘除目标入口，优雅停止，启动同一配置实例，在入口关闭时核验 | epoch不变、seq不归零；消费者自动启动，重投不重复计数 |
| 应用崩溃 | 该实例在途HTTP/发布结果可能未知；未ACK消息重投 | 不因应用崩溃单独提升 | 关闭该实例入口，确认依赖与签发器状态，重启并核验 | 已受理命令最终处理；不能把未知请求认定为未执行 |
| Redis网络短暂中断 | 签发失败；认证或内容缓存也可能503 | 同一run_id且READY时不需要 | 关闭入口，修复网络，status确认SQL/Redis纪元及guard | 不能因“连接恢复”就忽略run_id变化或缺Key |
| Redis重启、换地址、缺Key、异常TTL、耗尽或PAUSED | 拒绝新签发；已受理消息仍可处理 | 是 | 关闭入口，停止全部业务实例，隔离旧端点，按第6节恢复 | 完整旧Key从AOF恢复也必须拒签，新纪元版本严格更高 |
| MySQL重启且持久数据完整 | SQL回源和消费失败；已有缓存不证明数据健康 | 否 | 关闭入口，修复数据库，检查消费、重试与DLQ；故障持续可停止全部应用避免反复重试 | 提交未知经重投裁决；恢复后检查积压并有限量重放 |
| RabbitMQ重启且持久数据完整 | 发布失败或未知，消费暂时中断 | 否 | 关闭入口，恢复同一Broker数据，VerifyOnly核验拓扑，确认重连和消费 | 已确认消息保留；非确认请求可能已入队，不无限重发 |
| 整机重启 | 应用和依赖均不可用 | Redis进程变化时需要 | 入口保持关闭，先恢复持久依赖；停止/暂不启动业务实例，恢复纪元，再启动应用 | 避免服务自启动提前暴露；确认实际持久目录未换成空目录 |

关闭入口时允许继续消费，是为了完成已经受理的命令；这不要求在依赖长期故障时维持重试。SQL错误同次投递最多5次事务尝试，退避1/2/4/8秒，耗尽进入DLQ。需要暂停时停止进程，关闭端口本身不会暂停它。

正常停止使用部署现有的优雅退出方式；禁止通过清队列让进程“快速排空”。停止后仍可能有SQL已提交但未ACK的消息，重启时重复投递属于正常情况。

## 6. 签发器受控恢复

触发条件：签发器缺失、不一致、异常TTL、运行标识变化、序号耗尽或已PAUSED。服务影响：新签发暂停，恢复期间业务实例停止。需要提升纪元：是。

1. 关闭外部入口，停止**所有**业务实例。确认旧进程退出；如更换Redis地址，隔离旧端点，禁止新旧端点同时签发。
2. 确认目标MySQL及Redis数据没有备份回退；Redis为noeviction，运行标识可读取，恢复账号权限足够。涉及数据丢失则转第8节，不能继续套用普通恢复。
3. 执行status或只读SQL，取得当前SQL epoch，填写`$expectedEpoch`。
4. 执行recover。命令使用专用SQL连接命名锁，持久提交epoch+1，再在Redis初始化新epoch、seq=0、当前run_id及PAUSED，重新核验后条件激活READY。
5. 命令非零则保持全部业务实例停止、外部入口关闭。SQL若已经提交，纪元空洞允许存在；下次从当前SQL epoch再次提升，禁止在可能已使用的纪元内归零。迟到旧恢复不能覆盖或激活较低纪元。
6. status退出0后，启动应用实例；确认普通启动不再次增加epoch。按第9节验证后人工开放入口。

手动pause后同样通过上述流程恢复，当前工具不提供绕过纪元提升的resume命令。普通应用重启不执行recover。

## 7. 积压、队列满与死信

触发条件：积压持续增加、发布拒绝、重试增多、DLQ非空或转发受阻。服务影响：SQL展示落后，队列满时新发布不保证成功。仅这些情况不提升纪元。

1. 关闭或限制入口，检查MySQL锁等待/连接、Broker磁盘和队列容量、消费者状态。默认工作队列及DLQ各64MiB，不能通过无限扩容掩盖消费故障。
2. 如果不能继续安全消费，停止应用；修复依赖后重新启动，外部入口仍保持关闭。DLQ缺失或已满时，at-least-once死信转发会保留待转发消息，不能先ACK后另行复制。
3. 核验DLQ安全参数和绑定；仅在暂时错误原因消除后，执行有限量replay。保留原body、version、messageId及时间，确认重新发布后才ACK原死信。
4. 重放确认超时或断连时，保留原消息并按未知处理，后续重复由SQL裁决。格式错误、同版本状态冲突、计数不变量错误保持隔离并检查原因，不重新编号绕过错误。
5. 观察消费TPS、积压趋势、SQL状态与精确计数；必要时离线audit。对账全表聚合无文章反向索引，不作为频繁在线探针。

当前固定RabbitMQ镜像的内部死信重试周期可能达到180秒，修复后需要等待并观察；测试Broker缩短了该周期，不代表生产即时转发。Broker队头年龄不可用时标记未知，消费采样年龄不能冒充全队列最老消息年龄。

## 8. 数据损坏与备份回滚

触发条件：MySQL、Redis或RabbitMQ持久数据丢失、恢复旧快照、误换空卷。立即关闭入口并停止全部业务实例，保留现有卷、队列及故障证据，进入专项数据恢复。

这不属于“数据完整的服务重启”。SQL元数据可能回退，Redis序号或MQ已确认命令可能缺失；当前工具不能自动补回丢失消息，也不能证明回退后的SQL epoch+1大于所有历史签发版本。

不得仅根据关系表最大version推断历史签发上界，因为还有MQ中的命令、发布结果未知的命令和未落库版本。未确认历史纪元上界及各依赖恢复一致性前，禁止恢复签发，不清空队列、不清墓碑、不自动修数。恢复方案需另行制定并验证，本文不提供可直接执行的跨存储回滚修复命令。

## 9. 人工开放入口检查

- MySQL、Redis、RabbitMQ连接、持久化和资源状态正常，目标地址、命名空间、vhost与预期一致。
- status退出0，SQL/Redis epoch匹配，guard为READY且绑定当前run_id，检查没有消耗seq；本机管理health恢复正常。
- 拓扑VerifyOnly退出0，消费者已自动启动且有处理进展；积压可控并不持续增长。DLQ已检查，无未解释的不变量错误。
- 使用专用运维测试账号和文章，通过本机HTTP完成Session/CSRF、PUT受理202、SQL最终状态与精确计数、详情/列表装配、DELETE后的最终状态核验。游客likedByMe为false；不能只看202就确认SQL正常。
- 检查API/Confirm延迟、未知结果、重试/死信、缓存回源及拒签指标；不将用户或文章ID设为指标标签。展示缓存可能短暂落后，认证或内容缓存失败仍可能令整接口503，不承诺全站自动降级。
- 填写本次维护结果后人工开放外部入口；低流量观察再逐步恢复。任何关键检查失败继续关闭入口，不能靠反复重启或清空数据“恢复正常”。
