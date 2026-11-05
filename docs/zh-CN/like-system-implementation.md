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

后续批次在本文追加实际命令、配置和验证结果；未执行的测试不得记为通过。
