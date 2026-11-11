# 统一文章列表查询验收记录

日期：2026-11-11。实施位于本地master，按作者索引、作者查询与游标、本人列表替换、文档四批提交，不推送远端。

## 交付结果

- 公开搜索新增authorId，可单独查询作者或与关键词、Tag组合；ES只返回候选ID和排序值。
- 本人路由保持/api/users/me/articles，身份仅来自Session，固定查询本人公开文章；三个列表入口共用ES候选、缓存/主库回查与最终展示装配。
- 本人列表改为size/cursor和items/nextCursor，移除页码、精确total、SQL选页及COUNT；卡片统一包含浏览量、点赞数和个人点赞状态。
- ES新增long类型authorId，主库元数据、Mapping、脚本与对账贯通；默认物理索引为article_v2，保留原读写别名。
- 游标升级至版本2，绑定入口、作者、关键词、Tag、排序和页大小。本人游标不能跨账号或与公开查询混用；公开游标不绑定访问者。
- 回查作者不匹配时过滤候选并记录article.search.candidate.rejected（reason=author_mismatch），继续沿用最多三轮补齐和实际消费位置推进游标。

## 验证命令与结果

从仓库根目录运行：

```powershell
# 作者条件、游标协议及真实PIT查询。
./infra/test/run-search-tests.ps1 -Tests 'SearchCursorTests,ArticleSearchServiceTests,ArticleSearchHttpTests,ArticleSearchIndexIntegrationTests'
# 本人HTTP与原文章、点赞、缓存回归。
./infra/test/run-search-tests.ps1 -Tests 'SearchAuthorHttpIntegrationTests,ArticleMysqlIntegrationTests,ArticleHttpIntegrationTests,LikeHttpIntegrationTests,ArticleListHydratorTests,ArticleQueryServiceTests'
# 最终完整构建，启用专用ES停止/恢复实验。
./infra/test/run-search-tests.ps1 -Verify -Faults -Tests ''
```

前述专项及最终命令均退出0。另执行了作者索引专项和相关单元测试，最终clean verify重新编译并覆盖全部最新变更。主库索引快照与作者字段读取由实际执行的ArticleMysqlIntegrationTests覆盖。

| 最终完整构建统计 | 数量 |
|---|---:|
| 测试组 | 78 |
| 总用例 | 316 |
| 通过 | 300 |
| 失败 / 错误 | 0 / 0 |
| 条件跳过 | 16 |
| article.search包内用例 | 33，全部执行通过 |

跳过项为MediaV2IntegrationTests的12项真实媒体服务用例、LikeCapacityTests的1项容量用例、LikeRedisRestartTests的1项Redis重启用例，以及LikeHttpIntegrationTests的2项专门故障用例。本次没有启用这些独立专项条件；检索ES停止/恢复实验已实际执行，不计入跳过。验证生成backend/target/backend-0.0.1-SNAPSHOT.jar。

## 关键覆盖

- 真MySQL元数据与Tag处于同一RR快照，作者字段来自主库；ES文档完整创建，乱序写入不倒退、不复活隐藏文章，排名与元数据互不覆盖。
- 同版本作者损坏会被audit发现，普通repair不能越过版本保护覆盖；缺失文档可修复，新索引导入、算分、对账及原子别名切换通过。
- 两名真实Session作者及游客，作者单独/组合筛选、公开/隐藏文章过滤、本人作者参数伪造无效、三页连续遍历至nextCursor=null、跨账号/跨入口游标拒绝。
- 公开作者游标允许访问者变化；三个入口均正确组装个人点赞状态，游客及其他账号不继承个人状态；列表不调用浏览计数入口，不返回正文或隐藏字段。
- 作者不匹配过滤后补齐，游标不跳过预取候选；三轮过滤可产生空续页，旧版签名游标、篡改、条件变化、过期及BIGINT精度均有覆盖。
- 缓存热读不查文章主库状态，冷读合并回源；最终墓碑复查、隐藏/缺失负缓存、解锁顺序、失效令牌、四个回源名额及依赖失败保留覆盖。
- 专用ES容器停止期间，搜索、feed、本人列表均返回503，文章SQL写入成功；重启后DLQ重放完成索引同步。
- 通过真实应用子进程执行init两次、import、calibrate、audit，均正常退出；提交后进程退出、缓存TTL收敛及头像变化不污染共享文章缓存的原保证保持覆盖。

## 旧测试迁移与交付边界

原authorPage专属测试迁移到公共候选装配，移除依赖主库选页授权的负缓存冲突核对预期。数据库事务结果改由测试专用Mapper计数，不保留生产COUNT接口。原MySQL用例直接组合候选装配与展示装配，继续验证缓存TTL和头像更新；登录后的本人列表与点赞卡片断言迁入真实检索HTTP套件。非检索测试的WithoutSearchIndexing隔离保持不变。

最终测试报告位于backend/target/surefire-reports，主日志为backend/target/search-integration.log，维护子进程日志为backend/target/search-cli-*.log；后续clean会覆盖这些产物。性能样本为既有少量数据观测，不作为容量或生产SLA结论。

本次测试只使用检索专用四服务和随机索引/消息/缓存资源，未切换业务环境索引或开启业务查询。部署时按[统一查询运维说明](../../../public/operations/search/zh-CN/article-search-operations.md)显式准备article_v2并切换别名；旧索引为空无需字段回填，但MySQL已有文章仍需导入。

保持缓存命中不强查主库状态、提交后缓存维护尽力执行的既有一致性边界，允许短暂旧卡片。未新增私有文章、SQL发现降级、前端页面或推荐公式变更。
