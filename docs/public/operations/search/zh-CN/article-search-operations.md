# 文章统一查询与索引运维 v2

MySQL保存业务事实，Outbox/RabbitMQ可靠交接，ES是可重建索引。后端提供标题、单Tag、作者筛选、全局推荐和本人公开文章列表，不包含正文检索、个性化或反作弊。

## 接口

| 接口 | 参数 |
|---|---|
| GET /api/articles/search | q、tagId、authorId至少一个；sort=relevance/latest/recommended；size、cursor |
| GET /api/articles/feed | size、cursor，固定recommended |
| GET /api/users/me/articles | 登录后size、cursor，固定latest；作者仅来自Session |

公开搜索与feed支持游客；本人入口必须登录。q去首尾空白、最多100个Unicode字符；Tag与authorId为单个正整数ID，支持作者单独筛选或与关键词、Tag组合。size默认20、范围1～50。有关键词默认相关度，否则默认最新，无关键词不接受relevance。续页重新提交原参数和size，携带原nextCursor。本人入口只绑定size/cursor，请求中的authorId不参与身份选择；当前仅返回本人公开文章。

返回items/nextCursor，卡片包含现有小字段、viewCount、likeCount、likedByMe，不返回正文和精确total。本人入口移除原page/size/total响应字段和页码分页能力，不保留旧协议兼容层。个人字段不写共享缓存，游客likedByMe=false。列表不计浏览，公开详情成功读取才尝试计数。

相关度排序为_score/发布时间/ID倒序，最新为发布时间/ID倒序，推荐为rankScore/发布时间/ID倒序。标题match、Tag与作者精确匹配，固定visible=true；ES作者字段类型为long，回查投影中的作者还会再次核对，不匹配则过滤并计数。热门统一指推荐分。

PIT空闲期限2分钟，游标版本2，签名绑定入口、实际作者条件、查询、排序和size；本人实际作者来自Session，游标不可跨账号或跨入口，公开作者游标不绑定访问者。旧版游标按400无效处理。完整保存sort类型。刷新不带游标；空items且nextCursor非空仍可继续，nextCursor=null表示结束。参数或签名错误400，过期409/ARTICLE_CURSOR_EXPIRED，依赖故障503；不静默更换PIT，不回退SQL搜索。

本人、搜索、feed使用同一查询开关和依赖边界，关闭或ES故障均返回503。SQL保存成功不等待MQ/ES；缓存维护尽力执行，允许短暂旧卡片，热缓存不额外查文章状态。旧PIT候选与较新卡片可能来自不同时间，不保证展示内容的历史快照，也不承诺隐藏后即时拦截。发布后详情可按ID直接读取，列表需等待可靠同步和refresh；刷新时丢弃旧游标，开启新PIT。

## 同步、排名与配置

- V6新增meta_version及六字段Outbox，V7增加日期候选索引。创建最终v1/单事件，编辑与首次隐藏增版同事务写事件；重复隐藏、浏览和点赞不增版。
- Relay每轮10条，短RC领取、30秒令牌租约、事务外发送；Confirm ACK且无Return才删除。失败5～15秒退避，旧令牌不能修改新领取事件。
- 消费者实时GET预检查，再读主库短RR元数据/Tag快照，ES脚本原子防倒退；隐藏留墓碑，元数据与分数字段分开写，冲突内部重试3次。
- 推荐分：`ln(1+view7d)+ln(2)/48×hours(publishedAt-2020-01-01T00:00:00Z)`，只读SQL已落库pv，不合并Redis、不读取点赞，编辑不刷新发布时间。
- 业务日Asia/Shanghai。每轮结束后间隔5分钟扫描D/D-1/D-7候选，热度只统计D-6～D；建档后尽力首次算分，每日03:10全量校准。
- scoreVersion为计算开始时间戳，允许近时覆盖。分数不upsert，404跳过；临时失败最多3次，后续候选和每日校准补遗漏。
- 默认调度器归属保留，Relay与排名各用专用单线程。首次算分1线程/队列256，公开卡片SQL回源最多4并发，缓存锁等待预算300ms。既有浏览刷盘大事务未改造。

| 环境变量/配置 | 默认或要求 |
|---|---|
| ES_URL | http://127.0.0.1:9200 |
| ES_USERNAME / ES_PASSWORD | 环境提供，开发ES启用认证 |
| ARTICLE_SEARCH_ENABLED | false，验收后设true |
| ARTICLE_CURSOR_SECRET | 开查询需至少32字节，各实例一致 |
| ARTICLE_RANK_SCHEDULING_ENABLED | true，多实例只指定一个实例启用 |
| app.search.index-name | article_v2，初始化物理目标 |
| app.search.read-alias / write-alias | article_read / article_write |
| app.search.replicas | 0，生产按资源配置 |
| app.search.connect-timeout / response-timeout | 2s / 3s |
| app.search.target-index | 维护目标，默认index-name |
| app.search.replay-limit | 100，允许1～10000 |

凭证填写到.env.dev或.env.prod模板，使用时将所选模板复制为.env，或由部署工具注入系统环境变量，不提交真实文件。应用只读取.env，不自动读取模板；维护命令中的显式Profile须与当前配置一致。客户端沿用Boot的9.4.5，开发/测试服务端9.5.6，Rest5与ES私有Jackson 2不替换Web JSON。普通启动不建索引、不导入、不强检ES连通性。

消息前缀为`${app.auth.redis-prefix}:search`，冒号换成点后追加`.article.index.change.x/work.q/dead.x/dead.q`。路由为article.index.changed和article.index.dead。工作队列/DLQ各64MiB，quorum、reject-publish，工作队列至少一次死信转发。部署通过infra/dev/search-topology.ps1声明并核验专用绑定与队列参数，已有拓扑用-VerifyOnly；该脚本从RABBITMQ_USERNAME/PASSWORD/VHOST环境变量读取管理凭证，可指定-Namespace和-ManagementUrl。不加其他业务绑定/备用交换机；mandatory不能单独证明目标队列正确。

## 显式维护与上线

先在backend执行mvn clean package，从仓库根目录运行以下命令。只支持非Web模式，不自动停流量；命令成功退出0，失败非零，不启动监听、Relay或排名。

```powershell
# 显式创建article_v2，暂不绑定别名；兼容已有空旧索引的情况。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=init --app.search.target-index=article_v2
# 导入、算分、对账，均显式指定当前物理目标。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=import --app.search.target-index=article_v2
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=calibrate --app.search.target-index=article_v2
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=audit --app.search.target-index=article_v2
# 导入、算分、对账通过后原子切换读写别名，再开放查询。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=switch --app.search.target-index=article_v2
# 修复后自动复核，仍保留版本保护。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=repair --app.search.target-index=article_v2
# 确认重发成功后才ACK原死信。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=replay --app.search.replay-limit=100
```

导入包含隐藏墓碑，每批500篇、批间50ms，复用版本条件Bulk，可从头重跑，不重置分数。算分缺文档或失败项会使命令失败。audit分段mget对比文档缺失、版本、可见性和作者字段缺失/值，最多输出20个示例。repair后仍有差异则失败；等版本内容损坏、异常高版本用新索引重建处理，不无条件覆盖已有较高版本。

现有旧索引为空，直接新建article_v2，无须字段回填或在线双写；MySQL已有文章仍须完整导入，MySQL为空时导入、算分及对账可以得到零条结果。维护期间停写并暂停Relay/消费者和排名，避免增量仍写旧别名；新索引核对通过后切换别名并恢复。authorId不受旧等版本文档更新跳过问题影响。

本次启用顺序：完成测试并准备ES/消息拓扑 → 停旧Web实例及写入，暂停索引消费与排名 → 使用新版本非Web命令创建article_v2、导入、算分、对账并refresh → 切换两个别名 → 启动新版本Web实例并恢复消费，查询暂时关闭 → 确认Outbox及ready/unacked追平、对账通过 → 开查询、恢复写入并指定一个排名实例。保留的Outbox事件会在新别名上通过版本保护安全重放；不需要清空事件表。

重建使用维护窗口，停写、排空、暂停索引消费/排名后：

```powershell
# 显式target-index仅建新索引，不抢占旧别名。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=init --app.search.target-index=article_v3
# 对article_v3执行import/calibrate/audit后，原子切换两个别名。
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --spring.main.web-application-type=none --app.search.maintenance=switch --app.search.target-index=article_v3
```

切换后恢复，旧索引保留核对，后续维护显式指定当前目标。Outbox不是历史日志。长停或公式修改后calibrate；查询故障可关入口。回滚不删Outbox、不退迁移，不让旧的不写Outbox版本恢复文章写入。

## 验证与观测

```powershell
# 独立四服务、随机资源，包含文章/统计回归。
./infra/test/run-search-tests.ps1
# 加入专用ES容器停止/恢复等故障实验。
./infra/test/run-search-tests.ps1 -Faults
# 不限制测试类，执行完整Maven校验；未启用专项仍明确跳过。
./infra/test/run-search-tests.ps1 -Verify -Faults -Tests ''
```

测试库easy_community_search_test，端口17806/17879/17872/19200，凭证文件被Git忽略。停止实验只允许带ec.search.test标签的固定ES测试容器，不操作开发服务。

报告位于backend/target/surefire-reports，日志search-integration.log，性能样本search-performance.json记录三场景p95/p99、卡片SQL回源批次与采样到的连接池等待者。样本不代表容量上限或高并发SLA。

指标：article.index.outbox.count/oldest.seconds、publish.failure、consume.failure；article.rank.missing/failure、refresh/calibration.last.success.seconds；article.search.duration、article.search.candidate.rejected（reason=author_mismatch）。RabbitMQ管理面核对ready/unacked、DLQ及绑定，结合池等待和既有刷盘日志观察压力。
