# 异步会话级滚动摘要设计

## 目标

在不阻塞用户回复的前提下，将会话历史按增量游标生成可持续更新的滚动摘要，并保证多实例部署时同一 Agent 会话不会被重复摘要或互相覆盖。

## 当前问题

- `ConversationAnalysisWorker` 已经在后台触发 `ShortTermMemoryService.refreshIfNeeded`，但摘要生成没有 Redis 分布式锁，多实例可能同时调用模型。
- 摘要触发依赖消息数量和新增 Token，缺少以当前模型有效上下文窗口为基准的统一触发边界。
- 摘要结果已经使用 `memory_summary.end_message_id` 做覆盖游标，但需要继续强化“读取快照 → 模型生成 → 乐观提交”的并发保护。
- 摘要失败不能影响当前对话，也不能让任务永久占用；长期记忆不能因为每次摘要刷新而被高频写入。

## 设计方案

采用“现有数据库任务队列 + Redis 会话锁 + MySQL 增量游标”的方案，不引入 Redis Stream 或新的消费者链路。

### 1. 用户请求链路

用户消息和模型回复仍然立即写入 `chat_message`，现有分析任务继续通过 `AnalysisJobQueue` 去重和延迟投递。HTTP/SSE 回复不等待摘要，也不在请求线程中获取 Redis 锁。

### 2. 后台摘要链路

`ConversationAnalysisWorker` 领取任务后按以下顺序执行：

1. 根据 `agentId + sessionId` 生成锁键 `agent:memory:summary:{agentId}:{sessionId}`。
2. 使用 Redis `SET key token NX EX ttl` 尝试获取锁；获取失败时将任务短暂延后，不能直接丢弃。
3. 读取当前 `memory_summary`、`memory_state` 和游标之后的新消息。
4. 使用 `ContextBudgetPolicy` 根据模型上下文窗口、最大输出 Token 和安全余量计算有效输入预算。
5. 由 `RollingSummaryPolicy` 判断是否达到摘要条件：
   - 新增有效用户轮次不足时不摘要；
   - 未超过软阈值时不摘要；
   - 达到硬阈值时允许跳过等待轮次直接摘要；
   - 最近窗口消息始终保留，不放入本次摘要覆盖范围。
6. 将上一个摘要和新增消息发送给模型，只要求返回严格 JSON：`summary、goals、constraints、entities、pending、completed`。
7. 使用短事务和乐观校验提交：再次检查最新消息 ID、当前摘要版本和结束游标未变化，成功后递增版本并写入 `memory_summary`、`memory_state`。
8. 只有提交成功且通过长期记忆准入策略时，才将合格摘要写入 `LongTermMemoryPort`；普通摘要不会每轮重复写入长期记忆。
9. 使用 Redis Lua 条件删除，仅当 Value 等于本实例 token 时释放锁；异常、模型限流和 JSON 解析失败都记录任务状态并释放锁。

### 3. Redis 锁边界

在领域层定义摘要锁接口，基础设施层提供 Redis 实现，避免业务服务直接依赖 Redis 客户端。锁租约包含随机 token，默认 TTL 覆盖一次摘要模型调用；TTL 到期后允许其他实例接管，数据库乐观校验阻止旧实例覆盖新摘要。

建议接口：

```java
public interface MemorySummaryLock {
    Lease tryAcquire(String key, Duration ttl);
    void release(Lease lease);

    record Lease(String key, String token) {}
}
```

### 4. 上下文折叠关系

滚动摘要和推理前折叠是两个不同阶段：

- 滚动摘要：异步生成会话级笔记，写入 MySQL，记录覆盖的消息 ID 范围。
- 上下文折叠：每次模型推理前在内存副本上执行，按当前预算压缩旧消息和工具结果。
- 工具结果在数据库中保留完整原文；折叠副本保留 `tool_call_id`，模型需要详情时通过 `retrieve_tool_call` 按会话取回，不重复执行工具。

## 数据一致性与失败处理

- Redis 锁只负责同一会话的短时间互斥，不能替代数据库版本校验。
- 摘要模型调用失败不影响用户回复；任务进入可重试状态，达到最大次数后标记失败。
- 锁获取失败的任务延迟重试，避免多个实例忙等。
- 摘要结果不是事实来源，原始消息和工具结果仍以 MySQL 为准。
- 未审核 Feedback、候选 Case 和运行时错误不写入 Agent 长期画像。
- 摘要提交采用幂等游标，重复消费同一任务不会产生重复版本。

## 配置

新增配置项：

```yaml
agent:
  memory:
    summary-lock-ttl-seconds: 180
    summary-lock-retry-delay-seconds: 5
```

Redis 连接沿用 Spring Boot 标准配置，开发环境可使用 Docker Redis；单机未配置 Redis 时测试替身只用于单元测试，不作为生产降级实现。

## 验收标准

- 用户发送消息后，接口响应时间不等待摘要模型调用。
- 两个 Worker 同时处理同一 Agent/会话时，只有一个实例调用摘要模型。
- 摘要只覆盖上次 `end_message_id` 之后的新增内容，最近窗口保持可见。
- 摘要模型失败、限流或返回非法 JSON 时，原始会话仍可继续对话，任务可重试。
- 并发提交时旧摘要不能覆盖新摘要，版本号和游标保持单调递增。
- 工具折叠后可通过 `tool_call_id` 取回完整原文，且不会重新执行原工具。

## 测试范围

- `RollingSummaryPolicy`：软阈值、硬阈值、有效轮次、最近窗口和增量游标。
- `MemorySummaryLock`：加锁成功、重复加锁失败、token 不匹配不释放、TTL 到期。
- `ShortTermMemoryPersistenceService`：版本冲突、消息游标变化、重复提交幂等。
- `ConversationAnalysisWorker`：异步任务、锁获取失败延后、模型失败重试和锁释放。
- `ContextBudgetPolicy` 与 `MemoryFoldingPipeline`：模型窗口比例和工具调用 ID 保留。
