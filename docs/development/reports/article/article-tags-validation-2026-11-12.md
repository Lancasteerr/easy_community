# 文章标签 JSON 与本地目录验收

> 2026-11-12，Asia/Singapore。本轮仅实施后端、迁移、测试和文档；未修改前端。用户随后授权直接升级本机开发库，实际迁移及缓存重建结果见末节；旧关系表尚未物理删除。

## 实现结果

- V8新增单行目录版本；管理员新增与增版同事务，失败或重名回滚。
- 本地不可变快照支持前缀优先、包含补齐、ID解析；正常搜索和已知ID校验不访问标签SQL。
- 缺失ID先读主库版本，相同直接判非法，落后最多完整同步一次；加载不足目标版本或SQL故障返回503。
- 完整目录和版本来自同一个RR视图，所有刷新共用实例锁，等待300ms，失败冷却1秒。非Web维护不初始化目录任务。
- V9将旧关系按数值顺序回填article.tag_ids JSON，覆盖隐藏文章和无标签文章，超限中断而不截断。
- 文章读写及索引维护移除所有关系查询和DELETE/INSERT；ES元数据由同一行JSON提供。
- 文章缓存默认v4，只保存ID；详情、列表和编辑在事务/文章锁外装配名称，HTTP仍返回TagView。
- ES候选标签不匹配时过滤并保持既有游标推进规则；未增加SQL复核。

## 执行环境与命令

使用仓库 `infra/test/search-compose.yaml` 的专用MySQL 8.4、Redis、RabbitMQ、ES服务。迁移测试另外创建随机 `easy_community_tag_migration_test_*` 数据库，执行V8到V9后仅删除本例数据库。测试使用环境注入凭证，不记录凭证值。

执行并通过：

1. 目录、文章事务前校验的单元专项。
2. 文章与标签单元测试、真实文章事务/HTTP、目录一致性和迁移专项。
3. `infra/test/run-search-tests.ps1 -Verify`：clean、选定文章/搜索/索引/排名/点赞HTTP回归、打包验证。
4. `infra/test/run-search-tests.ps1 -Tests ArticleCacheIntegrationTests`：单独验证Redis缓存格式与恢复协议；该类现已加入脚本默认测试集合。

最后两次命令的保留XML报告合计：**128个用例，125通过，0失败，0错误，3跳过**。报告位于 `backend/target/surefire-reports`，打包产物为 `backend/target/backend-0.0.1-SNAPSHOT.jar`。最后一次 `search-integration.log` 对应缓存专项；本记录不把选定回归描述为所有后端集成测试全量执行。

三个未启用的故障注入用例：

- `LikeHttpIntegrationTests.hardProcessExitAfterCommitRedeliversWithoutDoubleCounting`：未设置IT_LIKE_FAULTS。
- `LikeHttpIntegrationTests.brokerRestartPreservesConfirmedUnconsumedCommands`：未设置IT_LIKE_FAULTS。
- `SearchFlowIntegrationTests.esOutageKeepsBusinessWritesAndDeadLettersCanBeReplayed`：IT_SEARCH_FAULTS=false。

## 关键验证证据

| 领域 | 验证内容 |
|---|---|
| 目录单元 | 10例：热搜索、NFKC、边界、空目录/未初始化、同版本拒绝、一次同步、冷却、版本倒退、SQL错误、并发补齐/超时 |
| 目录真实MySQL | 3例：创建/重名/回滚与版本原子性；Mapper零/一/三次查询；屏障控制的RR版本与目录一致视图 |
| JSON迁移 | 2例：排序、隐藏文章、空集合、超出JS安全整数的ID；CHECK拒绝非数组和六项，历史六项迁移失败且保留原数据 |
| JSON编解码 | BIGINT无损往返，不可变结果，非法类型、重复、非正数、溢出和超限不能伪装为空集合 |
| 文章事务 | 20例原有真实MySQL回归通过，覆盖媒体绑定、锁顺序、覆盖更新、隐藏、Outbox原子性、提交未知及缓存恢复 |
| HTTP | 标签搜索与空查询、管理员权限、limit边界、未知ID拒绝、原TagView字段、详情热缓存和编辑响应 |
| Redis | 6例，包含v3旧TagView载荷与v4命名空间隔离，新载荷只有tagIds，以及租约、墓碑和恢复协议 |
| ES | 标签候选过滤的游标规则、作者/标签组合检索、Outbox→MQ→ES→HTTP闭环及索引维护回归 |

## 发布边界

旧关系表本次保留但应用不再读写；物理删除需要后续独立迁移和发布。停止旧写实例、迁移核对及新缓存墓碑重建仍是部署步骤，本轮专项测试不代表其他环境已经完成升级。

当前契约以[标签接入与恢复说明](../../../public/guides/article/zh-CN/article-tags.md)为准。首轮写入后旧关系会陈旧，不能直接回滚旧程序；需要从当前JSON恢复关系，并使用新的兼容缓存版本。未来增加禁用/删除时必须重新设计缓存命中即通过的校验前提。

## 本机开发库实际升级

用户确认旧系统没有有效数据，授权直接迁移和重建后，于2026-11-12完成：

- 确认使用本机开发配置，目标为MySQL 8.0.42上的easy_community，升级前没有运行中的后端Java进程。
- 升级前Flyway为V7，包含21篇文章且全部隐藏，tag和article_tag均为空；ec:dev:article:v4未使用。
- 使用已验证的后端JAR、非Web模式和关闭管理员初始化的参数，成功执行V8、V9；再次校验九个迁移均成功。
- 21篇文章的tag_ids全部为[]，不存在非数组或非空集合，目录版本为1。
- Windows/JDK25首次初始化ES客户端遇到临时路径问题；指定项目已有短路径参数-Djdk.net.unixdomain.tmpdir=E:/temp后，离线重建命令正常完成并退出。
- ec:dev:article:v4的rebuild-complete=1，21篇隐藏文章的墓碑全部存在，缺失数为0；负缓存自然过期后命名空间保留22个键。

正常启动应用默认使用v4，无需再次运行该命名空间的重建命令。执行日志保留在backend/target/tag-dev-upgrade.log和backend/target/tag-dev-rebuild.log，核验程序位于target生成目录，不进入生产代码或Git。上述结果仅针对本机开发环境。
