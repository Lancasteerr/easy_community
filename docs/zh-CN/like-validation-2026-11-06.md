# 点赞模块验证记录（2026-11-06）

## 本轮：默认功能集成与无开关运维

本轮删除模块、写入和消费启用开关，点赞固定装配；外部入口由运维关闭和人工开放。分配器与SQL/MQ算法未改，不新增迁移，不重测或改写历史容量。

| 检查 | 本轮结果 |
|---|---|
| `./infra/test/run-like-tests.ps1 -Verify -Faults` | clean verify通过；224项，211通过、13按门禁跳过，零失败与错误，无测试方法排除 |
| 默认真实HTTP/MQ闭环 | LikeHttpIntegrationTests 11项通过，未配置点赞启用参数；包括未初始化时写503、健康异常但已受理消息仍落库 |
| 双实例轮换重启 | LikeApplicationRestartTests通过；消费者自动启动，普通重启不提升epoch、归零或额外消耗seq，后续版本继续增大 |
| 非Web运行隔离 | LikeRuntimeModeTests通过，无监听器/自动拓扑且不连接Broker；维护客户端保留供显式调用 |
| 实际JAR维护烟测 | status/pause/recover/audit/replay均通过，无旧开关参数；除replay外故意设置不可达MQ端口仍成功；未就绪status非零，成功检查不消耗seq |
| 环境示例契约 | LikeEnvironmentExamplesTests通过；五项RabbitMQ变量与后端绑定一致，无重复键、遗留开关或真实密码 |
| 真实故障演练 | 专用Redis/Broker重启、SQL连接故障、提交后ACK丢失及进程硬退出、DLQ保留与重放均通过 |
| 既有媒体持久化回归 | MysqlPersistenceTests 8项通过，不沿用旧媒体方法排除项 |

13项跳过为真实对象存储MediaV2IntegrationTests 12项及容量实验1项；本轮未执行真实COS/MinIO服务契约或新的压测。原始容量报告保留2026-11-05测量，不能作为本轮重新测量结果。

非MQ的Web集成测试仅替换监听容器并禁用自动RabbitAdmin拓扑，点赞业务保持真实装配；非Web集成不启动监听器。完整测试使用easy_community_like_test、测试vhost和随机前缀，未重启或清空开发及共享依赖。

部署、重启、宕机和人工开放步骤统一见[点赞运维手册](../public/operations/like/zh-CN/like-system-operations.md)。以下历史记录用于追溯，当前默认行为与验收结论以本节为准。

## 历史实施记录（默认集成改造前）

以下保留首次实施时的结果与当时限制。该版本采用功能与写入口默认关闭，且当时媒体超时断言不稳定；这些描述不代表当前版本。媒体测试已在后续提交修订，本轮已执行无排除项的完整回归。

- 两次`./infra/test/run-like-tests.ps1 -Verify -Faults`均发现`MysqlPersistenceTests.lockedFileMakesReadyTimeoutAndCannotCommitLater`失败：期望媒体状态ISSUED，实际READY。其测试与生产实现相对实施前的`e2ddfd4`均未修改；该用例单独重跑及旧版本的整个MysqlPersistenceTests类重跑通过。当前证据表明其结果依赖运行条件，未证明问题由点赞改动引入，也未修复该媒体问题。
- 随后在重建的专用测试环境中，明确排除上述一条方法，执行`clean verify`通过：212条，201通过、11跳过。没有在默认测试配置或生产代码中静默忽略该方法。
- 完成消息严格格式和隐藏竞态等最后补充后，针对受影响用例重跑通过，当前Surefire汇总为216条、205通过、11跳过、0错误/失败。此汇总由完整验证和后续针对性重跑组成，不冒充最后一次无排除项的完整执行。
- 11个跳过项为容量实验1个及原有真实对象存储MediaV2测试10个；容量实验已另行显式执行并通过，详见[实测报告](like-capacity-2026-11-05.md)。本期未执行COS/MinIO真实服务契约验收。
- 对照运行曾与回归共用测试库，产生管理员夹具冲突；对照已停止、临时工作树已移除，专用Compose项目已重建。上述最终通过结果来自随后顺序执行的验证。

历史显式排除命令（仅记录当时操作，不用于当前验收）：

```powershell
./infra/test/run-like-tests.ps1 -Verify -Faults -Tests '*,!MysqlPersistenceTests#lockedFileMakesReadyTimeoutAndCannotCommitLater'
```

当前全量命令为`./infra/test/run-like-tests.ps1 -Verify -Faults`，不沿用历史方法排除；后续媒体提交已明确提交未知的语义。

## 已验证边界

| 范围 | 证据 |
|---|---|
| SQL版本和计数 | 真实MySQL乱序/重复/墓碑、同版本冲突、同状态新版本、同关系并发、20个不同关系竞争同一计数；缺行回滚与audit只报告不修数 |
| HTTP与隔离 | 10个真实HTTP/MQ用例；Session、CSRF、UUID、429/Retry-After、角色、自赞、游客/个人响应隔离、operationId复用重新签发、隐藏竞态及受理后注销 |
| 发布与确认 | 真实无路由Return不返回202；Broker重启后已确认未消费消息保留；单测覆盖Confirm/Return组合、发布前失败、发布后失败与确认超时分类 |
| 提交与ACK | 真实KILL SQL连接验证整体回滚后重试；SQL提交后断开消费连接及独立进程硬退出，重投不重复计数；编排测试验证提交未知先重新比较版本再ACK |
| 死信 | DLQ不存在或已满时消息仍保留；恢复DLQ后可达；重放发布未知保留原死信，成功保留原版本再ACK；正常应用不监听DLQ |
| 分配器 | Redis7.2和7.4真实Lua验证，各6个分配器/缓存用例；缺Key/TTL/耗尽拒签、seq=0、超过2^53仍精确、并发唯一版本、迟到恢复拒绝 |
| 恢复 | 真实MySQL命名锁竞争、预期纪元错误、持久提升后初始化失败须再次提升；专用Redis真实重启且AOF中Key完整仍拒签，提升纪元后恢复 |
| 缓存与回源 | C/P胜出、固定TTL、STALE不重建缓存、SQL回填不覆盖新状态、缓存丢失迟到状态的寿命边界；50篇最多两次SQL、共享回源和并发预算、慢回填不耗尽SQL等待预算 |
| 迁移 | 专用随机数据库先执行V4、写公开/隐藏文章后执行V5，均得到零计数；不修改历史迁移 |
| 运维与指标 | 实际JAR的status/pause/recover/replay/audit、未就绪非零退出、状态检查不消耗seq；部署脚本核验实际Broker持久拓扑；指标仅本机独立管理端口可读 |

并发步骤由latch/barrier或明确连接/进程故障控制；只有退避与确定的慢缓存用例模拟等待。重启只允许专用容器固定名称、Compose项目及测试标签匹配。测试数据使用独立库、vhost和随机前缀，没有重启或清空开发及共享依赖。

## 历史上线边界

目标环境需重复长窗口容量及持久磁盘实验，确认限流和积压处理参数；本轮已经取得无排除项完整回归通过，目标环境的长期容量验收仍须单独执行。正式部署应按照[实施运维协议](../public/guides/like/zh-CN/like-system-implementation.md)先迁移、核验拓扑、人工初始化签发器，再启动默认集成的业务实例、完成本机核验并人工开放外部入口。单节点依赖不提供节点级容灾。

## 后续媒体超时契约修复

本文件保留上述实施时的失败、排除项和验证范围，不追改历史结论。后续已单独修正媒体测试的“超时必定未提交”假设，以零引用 ISSUED/READY 均可回收、已绑定文件受到保护为验收条件；当前修复及验证结果见[媒体验收补充](media-service-v2-validation.md)。
