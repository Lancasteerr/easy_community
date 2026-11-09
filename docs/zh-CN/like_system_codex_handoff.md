# 社区系统：文章点赞模块实施交接（定稿 V4）

> 实施补充（优先于下文示意代码）：已确认只交付本仓库后端，单节点依赖并验证多实例；人工停写恢复；不做HTTP去重、Redis乐观计数、消费批处理或计数分片。点赞为固定功能，不设置模块/写入/消费启用开关。关闭外部入口后允许后台消费；恢复纪元前必须停止全部业务实例。详细接口见 [like-system-implementation.md](../public/guides/like/zh-CN/like-system-implementation.md)，重启、宕机及人工开放流程见 [like-system-operations.md](../public/operations/like/zh-CN/like-system-operations.md)。
>
> 同版本不同状态必须隔离，不能被 `<=` 分支吞掉；关系初始化不使用 `INSERT IGNORE`。状态Hash固定600秒、计数缓存固定30秒，不滑动续期。STALE消息不得用原消息回写缓存。分配器增加绑定Redis运行标识的guard，重启后必须人工提升纪元。202只说明已可靠入队；HTTP结果未知不表示未执行。

> 目标：交给 Codex 按阶段实施。栈：Spring Boot、MySQL、Redis 7.2、独立 RabbitMQ。本文约定取代此前“Redis Streams / MQ 消费时从 Redis 读取目标状态 / 每次操作留一条历史 / SQL 分片计数 / Redis 总数定时覆盖 SQL”的方案。

## 0. 关键决策与一致性边界

1. **每个 `(user_id, article_id)` 在 MySQL `article_like_state` 中最多一行**；`liked` 保存当前状态，`version` 保存已经处理的最高版本；高版本直接覆盖这一行，**不保存逐次点击历史**。
2. 取消点赞时保留 `liked=0` 的状态行，**不 DELETE**。它既是当前未点赞状态，又是抵御旧点赞消息的版本标记（逻辑上的墓碑，而非额外历史记录）。联合主键只防重复行，不能单独防“删行后旧消息重新插入”。
3. 消费端先拒绝“同版本不同状态”的协议冲突；其余 `msg.version <= sql.version` 跳过并 ACK；高版本必须更新 `version` 和 `liked`，**即使 liked 与原值相同**；仅 `liked` 真正改变时才将 SQL 计数 `+1/-1`。关系更新和计数更新位于**同一 MySQL 事务**。
4. RabbitMQ 消息包含完整目标状态与版本，不能仅存 Redis 引用；无需保证队列严格顺序，SQL 行锁 + 版本比较处理乱序和重复投递。
5. MySQL 是最终事实来源；Redis 用户点赞状态、文章计数允许丢失，读时从 SQL 恢复；MQ 积压导致的短暂不一致可接受。**但版本分配器不能在同一纪元内回退或重复**。
6. 当前**不使用计数分片**，一篇文章对应 MySQL 一条精确计数记录。MQ 缓冲不等于无限吞吐，热门文章计数行仍是需压测的潜在瓶颈。
7. 不用定时任务把 Redis 计数绝对值覆盖到 MySQL。SQL 消费者在关系事务中维护精确计数；Redis 只是可丢失的展示投影，可短暂乐观自增，定时任务可做抽样对账。

## 1. 数据库 Schema

```sql
CREATE TABLE article_like_state (
    user_id       BIGINT NOT NULL,
    article_id    BIGINT NOT NULL,
    liked         TINYINT(1) NOT NULL DEFAULT 0,
    version       BIGINT NOT NULL DEFAULT 0,
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, article_id),
    CONSTRAINT ck_like_status CHECK (liked IN (0, 1)),
    CONSTRAINT ck_like_version CHECK (version >= 0)
) ENGINE=InnoDB;

CREATE TABLE article_like_count (
    article_id BIGINT NOT NULL PRIMARY KEY,
    like_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_like_count CHECK (like_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE like_version_epoch (
    id TINYINT NOT NULL PRIMARY KEY,
    epoch INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO like_version_epoch (id, epoch) VALUES (1, 0);
```

- 创建文章时在同一业务事务中初始化 `article_like_count(article_id, 0)`，避免消费时计数记录缺失。文章软删除不清理点赞关系或计数。
- 主键 `(user_id,article_id)` 支持信息流中“一个用户、多篇文章”的批量查询。当前不加 `(article_id,user_id)` 反向索引，除非以后需要查询点赞用户列表。
- 不加 `like_history`、`like_inbox` 表；若未来需要完整行为审计、可靠请求幂等另行设计。
- 新记录初值为 `liked=0,version=0`。首次“取消点赞”也可留下 `liked=0,version>0` 的记录。
- 不得在消费时执行 `DELETE FROM article_like_state`；历史上的 `liked=0` 行不得在 MQ 消息仍可能重放时按时间删除。

## 2. 版本生成与故障恢复

**不能直接让 Redis INCR 丢失后从 SQL 最新版本继续递增**：MQ 里可能还有尚未落库的更高版本。采用 `(epoch, seq)` 组合成单个 SQL `BIGINT version`。

- `epoch` 持久存于 `like_version_epoch`；`seq` 由 Redis 原子 `INCR like:version:seq` 生成；Redis 同时存 `like:version:epoch`。
- Java 组合：`version = ((long) epoch << 43) | seq`，约束 `0 <= epoch < 2^20`、`1 <= seq < 2^43`，始终为正的 Java long。版本顺序先比较 epoch，再比较 seq，相当于直接比较单个 version。
- 用 Redis Lua 在**同一次调用**检查 `epoch`、`seq` 两个 key 都存在且合法，再 `INCR`，返回 epoch 和 seq；若任一缺失，**拒绝签发**，不能隐式将 seq 重置为 1。
- 实际实现同时检查无TTL的guard：纪元、READY状态与当前Redis `run_id`必须匹配；Redis进程重启即拒签，完整恢复的旧key也不能绕过停写。初始化seq=0合法，正式签发seq必须大于0。
- 这两个 key 不设置 TTL，禁止缓存淘汰策略把它们驱逐；故障切换可能回滚 Redis 序号时也必须触发恢复流程。
- 恢复流程（管理操作）：关闭/阻塞所有点赞版本签发 → 协调唯一恢复执行者 → MySQL `epoch=epoch+1` 提交 → 用新 epoch 初始化 Redis `epoch/seq=0` → 确认所有 API 实例只从当前分配器领取版本 → 放开写入。**Spring 实例普通重启不得自动各自增加 epoch**。
- 对发布确认尚未返回的旧版本消息，恢复后即使到达也不会覆盖新纪元消息；消费端按 DB version 判断。
- 如果无法可靠发现 Redis 主从切换的序号回退或无法对旧签发者设置栅栏，必须先停写修复；不能声称此版本分配器具有无条件的强一致性。这是版本方案上线前的硬前提。
- Redis Lua 不要以浮点数解析大于 `2^53` 的完整 packed version。缓存中版本以**固定 19 位零填充十进制字符串**保存并按字典序比较，或拆成 epoch/seq 两段精确比较。

## 3. API 与发布链路

- `PUT /api/articles/{articleId}/like` 表示目标 `liked=true`。
- `DELETE /api/articles/{articleId}/like` 表示目标 `liked=false`。**不要使用 toggle API**。
- 需登录，沿用系统 Session/CSRF 保护；校验文章存在且可见，校验用户级限流。不从请求体信任 userId。
- 客户端每个逻辑操作生成 `operationId`（UUID），网络重试尽可能复用原操作；同一文章同一客户端只允许一个写请求在途，并合并快速点击后的目标状态。
- Spring 先领取 version，构造**自包含**消息，然后发布 RabbitMQ 持久消息，开启 publisher confirms 和 mandatory return/路由检测。只有确认成功路由且 broker 已确认持久接收，才返回 `202 Accepted`。确认结果未知时不能声称已成功；重发**必须复用相同 message/version**，不能把同一逻辑发布重试重新编号。
- Publish 成功后尽力写 Redis 乐观视图。Redis 写失败不影响已确认的 MQ 消息；202 仅表示已受理，不代表 SQL 提交完成。
- 对外返回示例：`{ "accepted": true, "liked": true, "version": "8796093022209" }`。version 若暴露给 JS 必须是字符串（避免大整数精度问题）。
- **不要因为缓存看起来与目标状态相同就直接省略新命令**：Redis/SQL 可能落后于排队中的旧操作。例如旧“点赞”未消费，Redis 丢失后 SQL 显示未点赞；用户此时“取消”仍必须进入 MQ，才能用更高版本阻挡旧点赞。
- `operationId` 在当前版只用于链路追踪和同一次发布重试；Redis 可做短期 HTTP 去重，但 Redis 丢失后不能保证跨故障的永久请求级幂等。严格请求幂等需要额外持久化记录，不在当前范围。

## 4. RabbitMQ 消息与拓扑

```json
{
  "schemaVersion": 1,
  "operationId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 42,
  "articleId": 1001,
  "desiredLiked": true,
  "version": "8796093022209",
  "occurredAt": "2026-11-04T15:00:00Z"
}
```

- Exchange 如 `article.like.command.x`，routing key `article.like.set`；初期**一个持久化工作队列**足够。生产多节点 HA 可使用 Quorum Queue；本地单节点可测试其故障语义。
- 消息使用 persistent delivery mode；生产端 Publisher Confirm + mandatory Return；消费端手动 ACK、适当 prefetch（例如 20，压测调优）、2～4 个 worker 起步。
- Worker **不从 Redis 读取目标状态**，只读取 MQ 完整消息。消息顺序不作为正确性依赖。
- SQL 事务成功提交或确认旧版本无须更改后才 ACK；暂时错误执行退避重试，不做无限快速 requeue；配置 DLX/DLQ，监控重试/死信。若高版本消息进 DLQ，后续低版本可能先落库，必须修复重放死信才能最终收敛。
- Quorum Queue 存在默认投递限制，达到后消息可能死信或丢弃；必须正确配置 DLQ 并监控，不能将其理解为“必定无限重试”。
- 消费者在数据库提交之后失效 Redis 缓存，Redis 失败也可 ACK（精确状态已在 SQL）；TTL 和回源最终修复。

## 5. 核心 SQL 消费事务（必须实现的正确性逻辑）

```java
@Transactional
public ApplyResult apply(LikeCommand m) {
    // 并发首次写入时，唯一主键使初始行最多插入一次。
    // 确保 article_like_count 对应行已在创建文章时建立。
    stateMapper.insertDefaultIfAbsent(m.userId(), m.articleId());
    LikeState old = stateMapper.selectForUpdate(m.userId(), m.articleId());

    // 同版本不同状态不是普通重复消息，必须隔离告警。
    if (m.version() == old.version() && m.desiredLiked() != old.liked()) {
        throw new IllegalStateException("conflicting version");
    }
    if (m.version() <= old.version()) {
        return ApplyResult.STALE_OR_DUPLICATE;
    }

    // 注意：哪怕 old.liked == desiredLiked，也必须提高 version。
    stateMapper.update(m.userId(), m.articleId(), m.desiredLiked(), m.version());

    int delta = (m.desiredLiked() ? 1 : 0) - (old.liked() ? 1 : 0);
    if (delta != 0) {
        int affected = countMapper.addDelta(m.articleId(), delta);
        if (affected != 1) throw new IllegalStateException("counter row missing/invalid");
    }
    return ApplyResult.APPLIED;
}
```

- `insertDefaultIfAbsent`: `INSERT INTO article_like_state(user_id,article_id,liked,version) VALUES (?, ?, 0, 0) ON DUPLICATE KEY UPDATE version=article_like_state.version`；只处理主键已存在，不使用IGNORE掩盖其他错误。
- `selectForUpdate`: `SELECT liked,version FROM article_like_state WHERE user_id=? AND article_id=? FOR UPDATE`，必须在同一事务内执行。
- `update`: `UPDATE article_like_state SET liked=?,version=?,updated_at=NOW(3) WHERE user_id=? AND article_id=?`。
- `countMapper.addDelta`: `UPDATE article_like_count SET like_count=like_count+? WHERE article_id=? AND like_count+? >= 0`。未更新恰好一行则整个事务回滚、告警，不能悄悄继续。
- APPLIED或DUPLICATE成功后，根据实际SQL `(version,liked)` 条件更新Redis为C并删除计数缓存；STALE分支不回写原消息。Redis不可用不回滚SQL。
- **切忌**仅判断 `old.liked == desiredLiked` 就提前返回；高版本的同状态命令必须推进 version，否则中间的旧相反状态消息会翻转最终状态。

## 6. Redis 键及使用方式

| Key | Type | Value | 目的 | 丢失/过期 |
|---|---|---|---|---|
| `like:state:{userId}` | Hash | `articleId -> {version19}|{liked0/1}|{P/C}` | 当前用户多篇文章的点赞状态，读时 `HMGET` | 整个Hash固定600秒；访问和字段更新不续期 |
| `like:count:{articleId}` | String | 点赞总数 | 列表/详情总数，`MGET` 批量读 | 例如 30 秒 TTL，丢失从 SQL 计数表回源 |
| `like:version:epoch` | String | 当前纪元 | 版本分配 | **不允许静默丢失/回退** |
| `like:version:seq` | String | 当前纪元序号 | Redis INCR | **不允许静默丢失/回退** |
| `like:version:guard` | Hash | epoch、runId、state | 运行标识和停写栅栏 | 无TTL；仅受控维护初始化 |

### 用户状态更新（Lua CAS）

- 发送方得到发布确认后写 `{version,liked,P}`；消费者事务成功后写 `{version,liked,C}`；SQL 批量回源时写 `{version,liked,C}`；不存在的关系视为 `version=0,liked=0,C`。
- 只有 incoming.version 更大才覆盖；同版本时 `C` 可以覆盖 `P`，但 `P` 不得覆盖 `C`；同版本却 liked 不同视为严重协议错误并记录告警。
- 版本采用零填充 19 位十进制字符串，Lua 比较固定长度的字符串，**不要使用 `tonumber` 比较 Java long 的完整版本**。
- Hash 回填也必须走版本比较，防止慢 SQL 查询覆盖新 Pending。
- 由于 Redis 7.2 不支持 Hash 字段 TTL，先以整个用户 Hash 设置短 TTL；未落库 P 也允许过期并短暂回退至 SQL，这符合本项目的一致性约定。

### Redis 总数的可选乐观自增

- 为减少复杂性，**正确性的基线是：Consumer 提交 SQL 计数，随后 `DEL like:count:{articleId}`；读取时从 SQL 回源**。
- 若希望 MQ 积压时更实时的全局计数，可在发布确认后由**一个 Redis Lua**原子地同时比较并更新用户状态与 count：只有缓存中存在可信的旧用户状态、消息版本更高、liked 真正变化且 count key 已存在时，才执行 `INCRBY +/-1`。不满足条件就只更新用户状态，计数由 SQL 回源或前端乐观展示。
- 此计数属于**可漂移、可回源的软实时投影**，不在每次并发/故障情况下承诺与 Pending 精确一致。不能用它定时直接覆盖 SQL；Consumer 的失效/TTL 用来修复。
- 如果本期更重视实施简单，可先**完全不做服务端乐观 INCR**，只让前端在点击时对自己看到的数字乐观 +/-1。待压测/体验要求明确后加入 Redis 软实时增量。

## 7. 读链路：信息流批量组装

1. 文章基础列表仍沿用当前信息流 Redis 快照/文章缓存，过滤软删除文章。
2. `MGET like:count:{id}` 批量取文章点赞总数；缺失的文章从 `article_like_count` 用 `WHERE article_id IN (...)` 批量查出，再写入短期缓存。
3. 已登录用户使用 `HMGET like:state:{userId} articleId...` 一次拿多篇文章状态；命中无论 P/C 均返回 liked。
4. 缺失关系执行一次 `SELECT article_id,liked,version FROM article_like_state WHERE user_id=? AND article_id IN (...)`；查不到按 false/version0 处理；使用 Lua 比较版本条件回填。
5. 游客不查询点赞关系，`likedByMe=false` 或 `null` 按前端协议定。
6. MQ 积压时优先展示 Redis P；Redis 丢失后允许短暂展示 SQL 旧值，待 MQ 消费后最终恢复。客户端写成功后自行维持本地操作状态，降低视觉回退。

## 8. 故障与异常边界

| 场景 | 规则 |
|---|---|
| 消息重复消费 | `version <= sql.version`：不更新关系/计数，ACK |
| 消息乱序 | 行锁保护版本判断；最高版本最终获胜，不要求顺序队列 |
| 同版本但目标状态冲突 | 理论上不应出现；记录严重错误/拒绝处理，不静默忽略 |
| Consumer SQL 已提交但 ACK 前宕机 | RabbitMQ 重投递，版本判断跳过 |
| Redis 状态/计数缓存丢失 | SQL 回源；允许比尚未消费的 MQ 旧 |
| Redis 分配器 key 丢失/主从切换可能回退 | **停止签发**，通过 MySQL epoch 受控切换，恢复后放行 |
| 发送后 Confirm 结果未知 | 不能当作确认成功；同一逻辑发布重试复用 version/operationId/消息内容 |
| Redis P 更新晚于 SQL C | 版本相同时 C 优先，P 不能降级 C |
| Redis SQL 回填晚于新 P | CAS 比较版本，旧 SQL 不能覆盖 |
| SQL / DB 不可用 | 消费者重试+MQ 积压；限制入口速率，监控最老消息年龄 |
| DLQ 中有高版本消息 | 告警、修复、重放原消息；否则无法承诺最终达到用户最新意图 |
| 文章软删除 | API 拒绝新点赞；已经确认的历史命令可继续完成，但文章读侧严格过滤。无需为每个赞锁 article 行 |
| 跨设备同时操作 | “最后”的定义是**版本分配顺序**，不必等于不同设备点击时钟顺序 |
| HTTP 重试生成新版本 | 可能将旧意图视为新操作；前端单关系串行+复用 operationId，若需跨故障严格请求级幂等需另加持久化设施 |
| 热门文章写热点 | 单行计数可能竞争；先压测，按实际影响做 Worker 批次合并与计数增量聚合，当前不分片 |

## 9. 测试用例与验收条件

**正确性（自动化集成测试）：**

1. v10 true → v12 true → v11 false：最终 true/v12，计数只增加 1。**同状态高版本也必须推进 version**。
2. v12 false 先到、v11 true 后到：表里只一行 false/v12，计数 0；不能物理删掉 false 行。
3. 同一消息投递两次（含 SQL 已提交 ACK 丢失）：计数只变化一次。
4. 两个消费者并发处理同一关系 v10/v11：最终 DB 持有最高版本，计数等于该行的 liked。
5. 先消费高版本然后低版本，旧版本返回 ACK 且不触发 Redis 状态回退。
6. Consumer 在更新关系后但计数前抛异常：整个事务回滚，绝不出现关系与计数不同步。
7. MQ 已确认但 Redis 写入失败：SQL 最终仍能按消息更新。
8. Redis 完全清空，MQ 仍有历史消息；新纪元再发布相反状态：最终最新纪元版本生效。
9. Redis C(v12) 已存在，迟到的 P(v12) 不得降级；SQL(v10) 晚回源不得覆盖 P(v13)。
10. 同用户一次读取 20 篇文章：点赞状态只做一次批量回源查询，不触发 20 次逐篇 SQL。
11. 文章已隐藏：新 API 请求被拒绝，查询信息流不展示；已受理的在途消息不导致异常循环。
12. 模拟 DLQ、高积压、Redis/SQL/RabbitMQ 断连与恢复，确认报警、人工重放、请求背压与版本栅栏有效。

**性能与监控：**

- 必测：突发点赞 QPS、平均/P95/P99 API 延迟、RabbitMQ 入队确认时间、消息积压/最老消息年龄、SQL 关系行锁等待、单篇文章计数行锁等待、Redis 状态/计数缓存命中率、Consumer TPS、DLQ 数量、SQL 回源 QPS。
- MQ 并不增加单条 SQL 计数记录的极限更新能力；如单篇爆款长期超过 SQL 消费吞吐，先做批次合并和按文章聚合净计数，未来再评估分片，**本期不分片**。

## 10. Codex 实施顺序

**阶段 A：数据库 + 消费核心**：建表、Mapper、Service；实现最高版本覆盖与事务内计数；先用单元/集成测试打通乱序和重复消息。

**阶段 B：RabbitMQ**：Exchange/Queue/DLQ、完整消息、持久发布、Publisher Confirm/Return、Manual ACK、消费者重试、指标与报警。

**阶段 C：版本分配器**：Redis epoch/seq 原子分配，MySQL epoch 恢复协调与写入栅栏；先完成故障测试再启用多实例。

**阶段 D：Redis 查询缓存**：带版本的用户 Hash 状态（CAS，P/C）；文章计数缓存；信息流 `HMGET/MGET` 批量回源。第一版可不实现服务端乐观计数，随后按需加入。

**阶段 E：API / 前端**：PUT/DELETE、Session/CSRF、202 语义、前端立即反馈、同一关系请求串行与快速点击合并。

**阶段 F：压测和恢复演练**：热点文章、Redis 全丢、RabbitMQ 重投递、SQL 回滚、DLQ 重放、版本恢复、缓存乱序写入。

### 不在本期范围

- 点赞行为全量历史/审计、单独的点赞事件流水表。
- 点赞总数分片、独立推荐/反作弊算法（可保留异步事件扩展接口）。
- 分布式跨存储事务、严格的跨故障 HTTP 请求去重。
- 定时将 Redis 的绝对点赞计数覆盖 SQL。

### 最终不可违反的四条不变量

1. `article_like_state` **每对用户和文章最多一行**，该行携带当前 `liked` 与已应用最高 `version`。
2. **`msg.version > db.version` 时总是更新 version**；仅状态变更修改计数；否则整条消息无 SQL 副作用。
3. **SQL 关系与 SQL 精确计数在同一事务**，Redis 永不作为强制持久化来源。
4. **版本不能回退/冲突**；Redis 缓存可丢，但签发器故障时必须停写并安全切换 epoch。
