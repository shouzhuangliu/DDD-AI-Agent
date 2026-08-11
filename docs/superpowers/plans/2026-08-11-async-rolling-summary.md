# 异步滚动摘要与 Redis 会话锁实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不阻塞用户回复的前提下，为会话级滚动摘要增加 Redis 分布式锁、模型窗口比例阈值、增量游标和并发幂等提交。

**Architecture:** 保留现有 `AnalysisJobQueue` 与 `ConversationAnalysisWorker` 后台链路。摘要服务在读取会话快照前获取按 Agent/会话隔离的 Redis 租约锁，使用 `memory_summary.end_message_id` 只处理未覆盖消息，模型生成完成后通过 MySQL 版本和最新消息 ID进行乐观提交；推理链路的 `MemoryFoldingPipeline` 继续只处理内存副本，不与异步摘要混用。

**Tech Stack:** Java 17、Spring Boot 3.4.3、Spring Data Redis/Lettuce、MyBatis、MySQL、JUnit 5、Mockito。

## Global Constraints

- 用户请求链路不得等待摘要模型调用、Redis 锁或摘要事务。
- Redis 锁必须使用随机 token，释放时只能删除本实例持有的 token。
- MySQL 原始消息、工具结果和 `tool_call_id` 是事实来源，摘要只是可重建的会话笔记。
- 摘要阈值必须根据模型上下文窗口比例计算，不新增固定 8000/16000 Token 阈值。
- 未审核 Feedback、候选 Case 和工具/模型运行错误不得写入 Agent 长期画像。
- 不修改用户已有的未跟踪文件：`docs/interview/`、`tmp/` 和简历 PDF。
- 每个任务完成后运行对应测试并提交一个中文 Conventional Commit。

---

### Task 1: 增加 Redis 摘要锁抽象与基础设施实现

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemorySummaryLock.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/redis/RedisMemorySummaryLock.java`
- Modify: `ai-agent-station-study-infrastructure/pom.xml`
- Modify: `ai-agent-station-study-app/pom.xml`
- Modify: `ai-agent-station-study-app/src/main/resources/application-dev.yml`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RedisMemorySummaryLockTest.java`

**Interfaces:**
- `MemorySummaryLock.tryAcquire(String key, Duration ttl)` 返回 `Lease` 或 `null`。
- `MemorySummaryLock.release(Lease lease)` 只释放 token 匹配的锁。
- Redis 实现使用 `StringRedisTemplate.opsForValue().setIfAbsent(key, token, ttl)` 加锁，使用 `DefaultRedisScript<Long>` 执行条件删除。

- [ ] **Step 1: Write the failing test**

```java
@Test
void acquiresWithNxAndReleasesOnlyMatchingToken() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(eq("agent:memory:summary:cs:sess-1"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(true);

    MemorySummaryLock lock = new RedisMemorySummaryLock(redis);
    MemorySummaryLock.Lease lease = lock.tryAcquire("agent:memory:summary:cs:sess-1", Duration.ofSeconds(30));

    assertNotNull(lease);
    lock.release(lease);
    verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(lease.key())), eq(lease.token()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-app -am '-DskipTests=false' '-Dtest=RedisMemorySummaryLockTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL because `MemorySummaryLock` and `RedisMemorySummaryLock` do not exist.

- [ ] **Step 3: Write minimal implementation**

Create the interface:

```java
public interface MemorySummaryLock {
    Lease tryAcquire(String key, Duration ttl);
    void release(Lease lease);
    record Lease(String key, String token) {}
}
```

The adapter must reject blank keys and non-positive TTLs, generate a UUID token, return `null` when `setIfAbsent` returns false, and release with a Lua script that deletes only when the stored value equals the lease token. Add `spring-data-redis` to infrastructure and `spring-boot-starter-data-redis` to the app. Add standard development defaults for `spring.data.redis.host` and `spring.data.redis.port` using `REDIS_HOST` and `REDIS_PORT` environment variables.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-app -am '-DskipTests=false' '-Dtest=RedisMemorySummaryLockTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: PASS, including NX acquisition and token-checked release.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemorySummaryLock.java ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/redis/RedisMemorySummaryLock.java ai-agent-station-study-infrastructure/pom.xml ai-agent-station-study-app/pom.xml ai-agent-station-study-app/src/main/resources/application-dev.yml ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RedisMemorySummaryLockTest.java
git commit -m "feat: 增加滚动摘要Redis分布式锁"
```

### Task 2: 修正滚动摘要策略的硬阈值和最近窗口

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/RollingSummaryPolicy.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/memory/RollingSummaryPolicyTest.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RollingSummaryPolicyTest.java`

**Interfaces:** 保持 `SummaryPlan` 字段和构造函数兼容；`plan(...)` 继续返回消息范围和估算 Token，但硬阈值可在未达到最近消息数时触发，前提是至少有一条旧消息可被摘要。

- [ ] **Step 1: Write the failing tests**

```java
@Test
void hardLimitCanTriggerBeforeRecentMessageCount() {
    RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 10, 20, 24, 4);
    List<RollingSummaryPolicy.MemoryMessage> messages = List.of(
            new RollingSummaryPolicy.MemoryMessage(1, "user", "这是一段非常长的库存反馈".repeat(20)),
            new RollingSummaryPolicy.MemoryMessage(2, "assistant", "已记录并准备分析"),
            new RollingSummaryPolicy.MemoryMessage(3, "user", "请继续"));

    RollingSummaryPolicy.SummaryPlan plan = policy.plan(messages, 0);

    assertTrue(plan.required());
    assertEquals(1, plan.startMessageId());
    assertEquals(1, plan.endMessageId());
    assertEquals(2, plan.recentStartMessageId());
}

@Test
void doesNotSummarizeWhenThereIsNoOlderMessageToFold() {
    RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 1, 2, 24, 0);
    assertFalse(policy.plan(List.of(new RollingSummaryPolicy.MemoryMessage(1, "user", "很长".repeat(100))), 0).required());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dtest=RollingSummaryPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL because the current policy returns early when `uncovered.size() <= retainRecentMessages` and never evaluates the hard threshold.

- [ ] **Step 3: Write minimal implementation**

Evaluate the hard limit before the recent-message early return. Reserve `recentCount = min(retainRecentMessages, uncovered.size() - 1)` and only produce a plan when at least one older message can be summarized. Soft-threshold plans still require the configured minimum meaningful user turns; hard-threshold plans may bypass that turn count.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dtest=RollingSummaryPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: PASS for both modules' rolling-summary tests.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/RollingSummaryPolicy.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/memory/RollingSummaryPolicyTest.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RollingSummaryPolicyTest.java
git commit -m "fix: 修正滚动摘要硬阈值与最近窗口"
```

### Task 3: 将 Redis 锁接入异步短期记忆服务

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/SummaryRefreshResult.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryServiceLockTest.java`

**Interfaces:**
- `ShortTermMemoryService.refreshIfNeeded(...)` 返回 `SummaryRefreshResult`，状态至少包含 `SAVED`、`NOT_REQUIRED`、`LOCK_BUSY`。
- 锁键格式固定为 `agent:memory:summary:{agentId}:{sessionId}`，TTL 和重试延迟从 `agent.memory.summary-lock-ttl-seconds`、`agent.memory.summary-lock-retry-delay-seconds` 读取。

- [ ] **Step 1: Write the failing test**

```java
@Test
void lockBusySkipsModelAndReportsRetryableResult() {
    when(lock.tryAcquire("agent:memory:summary:cs:sess-1", Duration.ofSeconds(180))).thenReturn(null);

    SummaryRefreshResult result = service.refreshIfNeeded("cs", "sess-1", "deepseek-v4-flash");

    assertEquals(SummaryRefreshResult.Status.LOCK_BUSY, result.status());
    verifyNoInteractions(messageDao, summaryDao, model);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dtest=ShortTermMemoryServiceLockTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL because the service currently has no lock dependency and returns `void`.

- [ ] **Step 3: Write minimal implementation**

Acquire the lease before querying messages; return `LOCK_BUSY` immediately when another instance owns it. Keep the existing model call and database persistence inside `try/finally`, release the lease in `finally`, and return `NOT_REQUIRED` when the policy does not require a new summary. Do not call the service from the HTTP/SSE request path.

In `ConversationAnalysisWorker`, handle `LOCK_BUSY` by calling `analysisJobDao.deferFailure(job.getId(), "RETRY", "summary lock busy", now.plusSeconds(retryDelay))` and return without Case evaluation. Other summary failures keep the existing warning-and-continue behavior only after the lock has been released.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dtest=ShortTermMemoryServiceLockTest,ConversationAnalysisWorkerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: PASS; lock contention causes retry scheduling, and exceptions still release the lease.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/SummaryRefreshResult.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryServiceLockTest.java
git commit -m "feat: 接入异步滚动摘要会话锁"
```

### Task 4: 强化摘要结果校验和长期记忆写入边界

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryPersistenceService.java`
- Modify: `ai-agent-station-study-app/src/main/resources/application-dev.yml`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/ShortTermMemorySnapshotTest.java`

**Interfaces:**
- 摘要 JSON 只接受 `summary、goals、constraints、entities、pending、completed` 六类字段。
- 摘要文本和数组项在入库前做长度上限与空值归一化，拒绝 Markdown 围栏、空摘要和非对象 JSON。
- 长期记忆写入保留现有 `MemoryQueryAdmissionPolicy`，但写入引用必须包含 `agentId/sessionId/version`，避免同一摘要版本重复写入向量存储。

- [ ] **Step 1: Write the failing tests**

Add tests that an invalid fenced response is rejected, an empty summary is rejected, and a valid summary persists only once for the same summary version.

```java
@Test
void summaryResponseMustBePlainJsonAndNonEmpty() {
    assertThrows(IllegalArgumentException.class,
            () -> service.parseAndNormalize("```json\\n{\\"summary\\":\\"库存反馈\\"}\\n```"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-app -am '-DskipTests=false' '-Dtest=ShortTermMemorySnapshotTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL because the current service validates the response inline but does not expose reusable normalization or a versioned long-term write contract.

- [ ] **Step 3: Write minimal implementation**

Extract a package-private normalization method used by the worker. Normalize whitespace, cap the summary at 2,000 characters, cap each structured list at 20 entries and each item at 500 characters, and pass a stable reference such as `session-summary:{agentId}:{sessionId}:v{version}` to `LongTermMemoryPort` only after a successful MySQL commit.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-app -am '-DskipTests=false' '-Dtest=ShortTermMemorySnapshotTest,MemoryQueryAdmissionPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: PASS with invalid model output rejected and valid summaries admitted at most once per version.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryPersistenceService.java ai-agent-station-study-app/src/main/resources/application-dev.yml ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/ShortTermMemorySnapshotTest.java
git commit -m "fix: 收紧滚动摘要结果与长期记忆准入"
```

### Task 5: 全链路回归、配置文档与提交推送

**Files:**
- Modify: `docs/superpowers/specs/2026-08-11-async-rolling-summary-design.md` only if implementation details require clarification.
- Modify: `docs/dev-ops/mem0-local.md` only if the existing local checklist lacks Redis startup instructions.

- [ ] **Step 1: Run targeted regression tests**

```bash
mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dtest=RollingSummaryPolicyTest,ShortTermMemoryServiceLockTest,ConversationAnalysisWorkerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: all targeted summary, lock and worker tests pass.

- [ ] **Step 2: Run the complete multi-module suite**

```bash
mvn '-DskipTests=false' test
```

Expected: exit code 0 and zero failures/errors in every module.

- [ ] **Step 3: Check formatting and repository scope**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended source, test, configuration and documentation files are staged. Preserve `docs/interview/`, `tmp/` and the resume PDF as untracked files.

- [ ] **Step 4: Commit and push**

If the regression requires a documentation correction, stage only the two explicitly listed documentation paths and commit it:

```bash
git add docs/superpowers/specs/2026-08-11-async-rolling-summary-design.md docs/dev-ops/mem0-local.md
git commit -m "docs: 补充异步滚动摘要运行说明"
```

Then push the task commits (and the optional documentation commit) without staging any other files:

```bash
git push origin main
```

Expected: remote `main` points to the verified commit and local/remote commit IDs match; the existing untracked user files remain untouched.
