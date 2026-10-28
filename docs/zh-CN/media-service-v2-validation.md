# 文件服务 V2 实施验收记录

日期：2026-10-29。此记录区分本地已验证行为和仍需真实 COS 环境验收的内容。

## 已通过

| 检查 | 结果 |
|---|---|
| `mvn -f backend/pom.xml clean verify` | 成功；普通模式43项通过，22项外部集成测试按门禁跳过，零失败与错误 |
| 真实文件集成 `MediaV2IntegrationTests` | 10项通过，零跳过、失败或错误 |
| 真实认证回归 `BackendApplicationTests` | 12项通过，零跳过、失败或错误 |
| `node --test backend/examples/media/upload.test.mjs` | 5项通过 |
| Caddy 2.10.2 配置验证 | 成功 |
| `node --check backend/examples/media/verify-cos.mjs` | 语法检查通过；未执行真实云端请求 |

普通模式跳过的22项已分别在外部模式执行通过，不能把普通模式的跳过本身当成集成通过。

真实文件集成使用 Java25、MySQL8、独立 Redis7.4、MinIO `RELEASE.2025-09-07T16-13-09Z` 与 Caddy `2.10.2-alpine`，覆盖：

- 真实 POST Policy、动态授权入口、CORS、Caddy JSON归一化及Key、MIME和文件长度篡改拒绝。
- 真实 MinIO Post 事件、Bearer通知、公开复制、READY提交和匿名公开读取，临时源保持私有。
- 无会话通知入口、错误token拒绝、无效社区Cookie不影响独立通知认证、旧入口404。
- 八字段主表、三字段引用、二进制UUID、引用外键、文章多实体共享、封面上限及事务回滚。
- 未就绪文件与其他上传者文件不能绑定，并发头像替换只保留当前引用且不改变认证版本。
- 文件行锁下的绑定与GC串行，刷新后的宽限期阻止旧扫描候选回收。
- 八个并发签发计数请求中，限额为二时只有两个成功，Redis计数不丢失。
- 普通维护调度器被占用时，慢通知正文仍在五秒内返回PROCESS_TIMEOUT；截止计时器不承担发布工作。
- READY更新被行锁阻塞时约1.1秒失败，不继续回写；已经复制的公开孤儿由GC删除，临时源保留供生命周期处理。

离线SDK传输测试额外验证COS HTTP200错误体不能被当成复制成功、审核仅明确PASS放行、未知结论拒绝、503不自动重试。Policy签名与官方COS签名器交叉验证；回调验签使用固定算法向量，尚不是云端真实签名样本验收。

测试新建专用数据库 `easy_community_media_v2_test_20261029`、`easy_community_auth_v2_test_20261029`，仅清理本次用户和对象，保留空的测试表结构供复查。测试容器及网络为独立资源，结束后停止清理。原数据库、原MinIO、原Redis和真实`.env`不修改。

## 真实COS尚未验收

本次没有真实COS凭证、已开通Callback的桶、可达生产回调与约定ckey，因此不能宣称生产验收通过。

生产部署前应使用专用测试账号与 `backend/examples/media/verify-cos.mjs`，验证真实验签、回调JSON转发、POST防覆盖、回调参数不可篡改及恰好10MiB图片审核。再以真实审核拒绝和复核样本检查失败分支，结合服务端耗时日志确认整条Callback在五秒内结束。离线响应替身与MinIO通过不能替代此项。

部署需要新库以及更新后的环境配置，不对既有Flyway历史执行repair或clean。具体配置和已接受限制见[文件服务V2](media-service.md)。
