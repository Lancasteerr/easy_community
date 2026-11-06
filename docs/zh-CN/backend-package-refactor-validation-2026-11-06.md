# 按业务能力整理包结构：验证记录

日期：2026-11-06。重构前基线：`4707e94`。

## 实施结果

- 文章按 content、view、tag、like 归包，common 仅保留错误与ID解析；content.application直属7个类。
- 点赞基础设施按数据库、Redis、MQ、配置、观测、维护分类；管理端安全配置归入shared.management。
- 媒体应用按upload、reference、lifecycle归组，MediaUrls承担公开地址转换，通知HTTP适配独立归组。
- 同步更新生产和测试包、主库Mapper显式扫描、XML全限定类型及中英文架构文档。通知Mapper仍由私有执行器加载。
- 保留事务代理、数据库结构、HTTP、配置键、Redis与MQ协议；不接入浏览计数新行为。

## 默认测试与构建

三个批次的默认 `mvn test` 均通过：218项，124通过、94按环境条件跳过，0失败、0错误。新增的一项测试验证标签应用入口对完整、空和缺失集合保持原有错误语义。

最终执行：

```powershell
mvn -f backend/pom.xml '-DargLine=-Djdk.net.unixdomain.tmpdir=E:/temp' clean verify
```

最终默认 `clean verify` 已通过并生成可执行JAR：218项，124通过、94跳过，0失败、0错误。

## 专用真实依赖验证

通过项目现有脚本启动独立MySQL、Redis和RabbitMQ测试容器，不使用开发数据库或共享消息空间：

```powershell
./infra/test/run-like-tests.ps1 -Verify
```

重构版本218项：203通过、1失败、14跳过、0错误。文章提交与回滚、标签拒绝、关闭点赞时初始化计数、浏览统计刷盘、点赞发布/消费/恢复，以及认证和主库装配用例均已实际执行。唯一失败为 `MysqlPersistenceTests.lockedFileMakesReadyTimeoutAndCannotCommitLater`：预期ISSUED，实际READY。

将重构前基线解包到独立临时目录，并使用同一专用依赖执行相同完整验证，217项中202通过、同一个用例失败、14跳过。该用例单独执行连续4次通过，完整套件则在重构前后均复现失败，表明问题受运行时序影响。没有修改或跳过该断言。

`NotificationFiles`与`ProcessingDeadline`相比基线仅改变包名或导入，方法体一致。现有通知READY更新使用自动提交，客户端读超时与服务端取消/提交存在竞态，超时本身不能证明未提交。该行为及测试契约应单独修复，不混入本次代码组织调整。

未执行的14项：媒体MinIO/Caddy端到端10项、点赞故障3项、容量1项。媒体端到端需要独立存储和代理配置；故障与容量需要显式开关。上述跳过项不计作通过。

## 静态核验

所有Java包名与目录一致；生产Mapper XML的namespace、resultType及嵌套类型均能定位源码；未发现旧文章分层或旧点赞基础设施全限定引用；文章提交不再访问标签基础设施。未修改SessionPrincipal与Role，不迁移会话命名空间。
