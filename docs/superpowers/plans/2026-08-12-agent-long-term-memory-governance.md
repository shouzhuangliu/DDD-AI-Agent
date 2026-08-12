# Agent Long-Term Memory Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有混合召回改造成按 Agent 隔离、人工审核发布、可追溯且支持 BGE-M3/pgvector 渐进式召回的长期记忆系统。

**Architecture:** MySQL 保存候选、证据、正式卡片、版本和 Outbox，是长期记忆权威数据源；pgvector 仅保存已发布卡片的可重建语义索引。普通会话只维护短期滚动摘要并异步产生候选，只有人工确认规则或已解决 Case 可以发布；运行时先搜索轻量索引，再按 ID 取回完整正文。

**Tech Stack:** Java 17、Spring Boot、Spring AI Tool、MyBatis、MySQL、Redis、PostgreSQL/pgvector、BGE-M3、JUnit 5、Mockito

## Global Constraints

- 长期记忆只按 `agent_id` 隔离，当前不引入用户、租户和 Topic 维度。
- 普通会话摘要、未审核 Feedback、候选 Case、工具异常和模型运行观察不得进入正式长期记忆。
- 只有人工确认规则和状态为 `RESOLVED` 的 Case 可以发布。
- MySQL 是权威数据源；pgvector 只是可重建索引，索引失败不能阻塞对话或 Case 状态迁移。
- 所有正式记忆必须关联可验证证据，并能反查会话、消息、工具调用或 Case。
- 所有实现步骤遵循测试先行；只提交本任务文件，不包含 `tmp/`、简历 PDF 和现有未跟踪文件。

---

## File Structure

### 新建的领域与持久化文件

- `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemoryPublicationPolicy.java`：长期记忆准入和状态迁移规则。
- `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/AgentMemoryCatalogPort.java`：轻量搜索与按 ID 读取正文的领域端口。
- `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryCandidate.java`：候选记忆 PO。
- `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryEvidence.java`：记忆证据 PO。
- `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryCard.java`：正式记忆卡片 PO。
- `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryExtractionCursor.java`：会话抽取游标 PO。
- `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryIndexOutbox.java`：向量索引事件 PO。
- 对应的 `I*Dao.java` 与 `mybatis/mapper/*_mapper.xml`：数据访问。
- `ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260812__agent_long_term_memory_governance.sql`：表结构与索引。

### 新建的应用服务与工具

- `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCandidateService.java`：候选保存、审核和发布编排。
- `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractor.java`：基于增量游标抽取会话候选。
- `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogService.java`：MySQL + pgvector 混合检索和正文读取。
- `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryIndexWorker.java`：消费 Outbox 并写入 pgvector。
- `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/SearchAgentMemoryTool.java`：搜索最多五条轻量索引。
- `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/GetAgentMemoryTool.java`：按 ID 获取最多三条正文。

### 主要修改文件

- `ShortTermMemoryService.java`：停止将会话摘要写入长期向量库。
- `LongTermMemoryRecallService.java`：停止混入历史 `memory_summary`，改为正式卡片预览。
- `CaseMemoryPublisher.java`：已解决 Case 创建候选，不再直接写 Profile/向量库。
- `AgentMemoryProfileService.java`：从已发布卡片编译版本化 Profile。
- `ReActToolAllowlistPolicy.java`、`ReActExecuteStrategy.java` 和 `SubagentExecutionService.java`：装配长期记忆渐进式工具。
- `AgentOperationsController.java`：增加候选审核、发布、检索和正文读取接口。

---

### Task 1: 隔离短期摘要与长期记忆

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/LongTermMemoryRecallService.java`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/LongTermMemoryRecallServiceTest.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryBoundaryTest.java`

**Interfaces:**
- Consumes: 现有 `IMemorySummaryDao`、`LongTermMemoryPort`。
- Produces: 明确边界——摘要只写 MySQL，长期召回不读取 `memory_summary`。

- [ ] **Step 1: 写摘要不进入长期存储的失败测试**

```java
@Test
void savedRollingSummaryMustNotBeStoredAsLongTermMemory() {
    service.refreshIfNeeded("inventory", "session-1", "deepseek-chat");
    verifyNoInteractions(longTermMemoryPort);
}
```

- [ ] **Step 2: 写召回不返回历史会话摘要的失败测试**

```java
@Test
void longTermRecallMustNotMixSessionSummary() {
    when(memorySummaryDao.queryByAgent("inventory", 5)).thenReturn(List.of(
            MemorySummary.builder().agentId("inventory").sessionId("s1")
                    .summary("尚未审核的临时判断").status("ACTIVE").build()));
    when(longTermMemoryPort.retrieve("inventory", "inventory", "库存", 5)).thenReturn(List.of());

    assertTrue(service.recall("inventory", "库存", 5).isEmpty());
}
```

- [ ] **Step 3: 运行定向测试并确认失败**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=ShortTermMemoryBoundaryTest,LongTermMemoryRecallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因分别是摘要仍调用 `LongTermMemoryPort.store`、召回仍返回 `SESSION_SUMMARY`。

- [ ] **Step 4: 删除两条错误链路**

在 `ShortTermMemoryService` 删除 `MemoryQueryAdmissionPolicy` 和 `LongTermMemoryPort` 依赖以及
`SESSION_SUMMARY` 写入块；在 `LongTermMemoryRecallService` 删除 `IMemorySummaryDao` 依赖和
`queryByAgent` 合并逻辑。保留短期摘要表和当前会话折叠功能。

- [ ] **Step 5: 运行测试并提交**

Run: 使用 Step 3 命令，Expected: PASS。

```powershell
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/LongTermMemoryRecallService.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory
git commit -m "fix: 隔离短期摘要与长期记忆召回"
```

### Task 2: 建立长期记忆权威数据模型

**Files:**
- Create: `ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260812__agent_long_term_memory_governance.sql`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryCandidate.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryEvidence.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryCard.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryExtractionCursor.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryIndexOutbox.java`
- Create: corresponding DAO interfaces and MyBatis mapper XML files
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/LongTermMemorySchemaContractTest.java`

**Interfaces:**
- Consumes: MyBatis mapper conventions and current MySQL migration loader.
- Produces: `IAgentMemoryCandidateDao`、`IAgentMemoryEvidenceDao`、`IAgentMemoryCardDao`、`IAgentMemoryExtractionCursorDao`、`IAgentMemoryIndexOutboxDao`。

- [ ] **Step 1: 写数据库契约失败测试**

```java
@Test
void migrationDefinesGovernedMemoryTablesAndAgentScopedKeys() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/sql/mysql/migrations/"
            + "V20260812__agent_long_term_memory_governance.sql"));
    assertTrue(sql.contains("agent_memory_candidate"));
    assertTrue(sql.contains("agent_memory_evidence"));
    assertTrue(sql.contains("agent_memory_card"));
    assertTrue(sql.contains("agent_memory_extraction_cursor"));
    assertTrue(sql.contains("agent_memory_index_outbox"));
    assertTrue(sql.contains("agent_id"));
    assertTrue(sql.contains("memory_key"));
}
```

- [ ] **Step 2: 运行测试确认迁移缺失**

Run:

```powershell
mvn -pl ai-agent-station-study-app -Dtest=LongTermMemorySchemaContractTest test
```

Expected: FAIL with `NoSuchFileException`。

- [ ] **Step 3: 创建五张表与关键约束**

迁移必须包含：

```sql
UNIQUE KEY uk_memory_candidate_source
  (agent_id, memory_type, memory_key, source_type, source_id),
UNIQUE KEY uk_memory_card_version (agent_id, memory_id, version),
UNIQUE KEY uk_memory_cursor (agent_id, session_id),
UNIQUE KEY uk_memory_outbox_event (event_id),
KEY idx_memory_card_lookup (agent_id, status, memory_type, effective_at)
```

`source_id` 统一保存 Case ID 或 `sessionId:endMessageId`；所有文本列使用 `utf8mb4`。

- [ ] **Step 4: 创建 PO、DAO 和 Mapper**

DAO 至少提供以下方法：

```java
int insertIgnore(AgentMemoryCandidate candidate);
AgentMemoryCandidate queryByCandidateId(String agentId, String candidateId);
int transition(String agentId, String candidateId, String fromStatus, String toStatus,
               String reviewedBy, String reviewComment, LocalDateTime reviewedAt);

List<AgentMemoryCard> searchPublishedIndex(String agentId, String query, int limit);
List<AgentMemoryCard> queryPublishedByMemoryIds(String agentId, List<String> memoryIds);
AgentMemoryCard queryLatestByKey(String agentId, String memoryKey);

int advance(String agentId, String sessionId, long expectedMessageId, long nextMessageId);
AgentMemoryIndexOutbox claimNext();
```

- [ ] **Step 5: 运行模块测试并提交**

Run:

```powershell
mvn -pl ai-agent-station-study-app,ai-agent-station-study-infrastructure -am -DskipTests compile
mvn -pl ai-agent-station-study-app -Dtest=LongTermMemorySchemaContractTest test
```

Expected: BUILD SUCCESS and test PASS。

```powershell
git add ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260812__agent_long_term_memory_governance.sql ai-agent-station-study-app/src/main/resources/mybatis/mapper ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/LongTermMemorySchemaContractTest.java
git commit -m "feat: 建立Agent长期记忆治理数据模型"
```

### Task 3: 实现候选审核与发布状态机

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemoryPublicationPolicy.java`
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCandidateService.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCandidateServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的候选、证据、卡片和 Outbox DAO。
- Produces: `submitCandidate`、`approve`、`reject`、`publish`、`retireByCaseId`。

- [ ] **Step 1: 写准入与越权失败测试**

```java
@Test
void unresolvedCaseCannotBePublished() {
    assertThrows(IllegalStateException.class,
            () -> service.publish("inventory", "candidate-1", "developer"));
}

@Test
void approvedResolvedCasePublishesCardAndOutboxAtomically() {
    PublishedMemory result = service.publish("inventory", "candidate-2", "developer");
    assertEquals("PUBLISHED", result.status());
    verify(cardDao).insert(any());
    verify(outboxDao).insert(any());
}

@Test
void evidenceFromAnotherAgentIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> service.submitCandidate(crossAgentRequest()));
}
```

- [ ] **Step 2: 运行测试确认服务缺失**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryCandidateServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because types do not exist。

- [ ] **Step 3: 实现纯领域状态机**

```java
public enum CandidateStatus { EXTRACTED, PENDING_REVIEW, APPROVED, REJECTED, PUBLISHED }

public void requireTransition(CandidateStatus from, CandidateStatus to) {
    boolean allowed = switch (from) {
        case EXTRACTED -> to == PENDING_REVIEW;
        case PENDING_REVIEW -> to == APPROVED || to == REJECTED;
        case APPROVED -> to == PUBLISHED;
        default -> false;
    };
    if (!allowed) throw new IllegalStateException("非法长期记忆状态迁移: " + from + " -> " + to);
}
```

策略同时检查：Case 来源必须为 `RESOLVED`；`SESSION` 来源必须经过人工审核；运行异常类型不能发布。

- [ ] **Step 4: 实现事务编排与证据校验**

`publish` 使用 `@Transactional("mysqlTransactionManager")` 完成：锁定候选、校验状态、创建新卡片版本、
废弃旧版本、复制证据、写 Outbox、迁移候选状态。不得在事务内调用 embedding 模型或 pgvector。

- [ ] **Step 5: 测试并提交**

Run: 使用 Step 2 命令，Expected: PASS。

```powershell
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemoryPublicationPolicy.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCandidateService.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCandidateServiceTest.java
git commit -m "feat: 实现长期记忆候选审核发布状态机"
```

### Task 4: 接入会话增量候选抽取

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractor.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractorTest.java`

**Interfaces:**
- Consumes: `IChatMessageDao`、Skill 绑定上下文、Task 2 游标 DAO、Task 3 `submitCandidate`。
- Produces: `ExtractionResult extractIfEligible(String agentId, String sessionId, String modelId)`。

- [ ] **Step 1: 写有效信息与游标测试**

```java
@Test
void singleCharacterConversationDoesNotCreateCandidateOrAdvanceCursor() {
    when(messageDao.queryBySessionId("s1")).thenReturn(messages("user", "1"));
    assertEquals(SKIPPED_LOW_INFORMATION, extractor.extractIfEligible("inventory", "s1", "m1").status());
    verifyNoInteractions(candidateService);
}

@Test
void failedExtractionDoesNotAdvanceCursor() {
    when(modelExtractor.extract(any())).thenThrow(new RuntimeException("429"));
    assertThrows(RuntimeException.class, () -> extractor.extractIfEligible("inventory", "s2", "m1"));
    verify(cursorDao, never()).advance(anyString(), anyString(), anyLong(), anyLong());
}

@Test
void successfulCandidateCommitAdvancesCursorOnce() {
    extractor.extractIfEligible("inventory", "s3", "m1");
    verify(candidateService).submitCandidate(any());
    verify(cursorDao).advance("inventory", "s3", 0L, 18L);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=ConversationMemoryCandidateExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because extractor is absent。

- [ ] **Step 3: 实现严格 JSON 抽取契约**

模型输出字段固定为：

```json
{
  "eligible": true,
  "memoryType": "BUSINESS_RULE",
  "memoryKey": "inventory:reservation-release-condition",
  "title": "预占库存释放条件",
  "summary": "订单创建失败后应释放预占库存",
  "content": {"rule":"...","applicableWhen":"...","exceptions":[]},
  "evidence": [{"messageId":18,"toolCallId":"","quote":"..."}],
  "confidence": 82
}
```

服务端拒绝无绑定 Skill、无用户/运维证据、引用不匹配、纯运行错误、实时数值和低信息密度输出。

- [ ] **Step 4: 接入现有异步 Worker**

在 `ConversationAnalysisWorker` 完成短期摘要和 Case 评测后调用抽取器。抽取异常记录日志并由现有
任务重试机制处理，但不能影响已经保存的用户回复；重复处理依靠来源唯一键和游标保证幂等。

- [ ] **Step 5: 测试并提交**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=ConversationMemoryCandidateExtractorTest,ConversationAnalysisWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

```powershell
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractor.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractorTest.java
git commit -m "feat: 接入会话长期记忆候选增量抽取"
```

### Task 5: 将已解决 Case 改为受控记忆候选

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseMemoryPublisher.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AgentMemoryProfileService.java`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/CaseMemoryPublisherTest.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/AgentMemoryProfileCompilationTest.java`

**Interfaces:**
- Consumes: Task 3 `AgentMemoryCandidateService` 和正式卡片 DAO。
- Produces: resolved Case candidate；`compileLatest(String agentId)` 画像编译方法。

- [ ] **Step 1: 把现有直接写画像测试改为候选测试**

```java
@Test
void resolvedCaseCreatesReviewableMemoryCandidateInsteadOfDirectVectorWrite() {
    publisher.publish(caseItem(), "RESOLVED", "开发人员确认修复");
    verify(candidateService).submitResolvedCaseCandidate(eq(caseItem()), eq("开发人员确认修复"));
    verifyNoInteractions(longTermMemoryPort);
}
```

- [ ] **Step 2: 写 Profile 只读取已发布卡片的失败测试**

```java
@Test
void profileContainsOnlyPublishedCards() {
    when(cardDao.queryPublishedByAgent("inventory")).thenReturn(List.of(publishedResolvedCaseCard()));
    AgentMemoryProfile profile = service.compileLatest("inventory");
    assertTrue(profile.getProfileJson().contains("case-28"));
    assertFalse(profile.getProfileJson().contains("candidate-29"));
}
```

- [ ] **Step 3: 运行测试确认旧行为失败**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=CaseMemoryPublisherTest,AgentMemoryProfileCompilationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because Publisher still writes Profile and vector directly。

- [ ] **Step 4: 实现 Case 候选和 Profile 物化视图**

`CaseMemoryPublisher` 只为 `RESOLVED` Case 构造 `RESOLVED_CASE` 候选；候选证据引用原 Case 证据。
`AgentMemoryProfileService` 查询 `PUBLISHED` 卡片，按类型编译 `business_rules`、
`failure_patterns`、`resolution_patterns` 和 `capabilities`，版本号随正式卡片发布递增。

- [ ] **Step 5: 测试并提交**

Run: 使用 Step 3 命令，Expected: PASS。

```powershell
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis
git commit -m "feat: 将已解决Case纳入受控长期记忆发布"
```

### Task 6: 通过 Outbox 建立 pgvector 可重建索引

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryIndexWorker.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/LongTermMemoryPort.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/PgVectorLongTermMemoryPort.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/NoopLongTermMemoryPort.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/Mem0LongTermMemoryPort.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryIndexWorkerTest.java`

**Interfaces:**
- Consumes: Task 2 Outbox DAO、正式卡片 DAO、现有 BGE-M3 `VectorStore`。
- Produces: `index(PublishedMemoryDocument)`、`delete(agentId, memoryId, version)` 和可重试 Worker。

- [ ] **Step 1: 写索引最终一致性失败测试**

```java
@Test
void failedEmbeddingLeavesOutboxRetryable() {
    doThrow(new RuntimeException("embedding unavailable")).when(indexPort).index(any());
    worker.processNext();
    verify(outboxDao).markRetry(eq("event-1"), contains("embedding unavailable"), any());
    verify(outboxDao, never()).markDone("event-1");
}

@Test
void indexedDocumentContainsOnlyLocatorMetadata() {
    worker.processNext();
    verify(indexPort).index(argThat(document -> document.agentId().equals("inventory")
            && document.memoryId().equals("mem-1") && document.version() == 2));
}
```

- [ ] **Step 2: 运行测试确认 Worker 缺失**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryIndexWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL。

- [ ] **Step 3: 明确索引端口语义并实现 Worker**

pgvector metadata 仅包含：`agent_id`、`memory_id`、`version`、`memory_type`、`status`、
`source_case_id`。向量文本使用 `title + description + 可检索摘要`，不将完整证据和审计信息复制到
向量库。Worker 成功后 `markDone`，失败按指数退避 `markRetry`，超过上限标记 `FAILED` 并允许人工重放。

- [ ] **Step 4: 增加 retire/delete 索引处理**

卡片进入 `SUPERSEDED` 或 `RETIRED` 时写删除事件；召回同时在 MySQL 回源阶段校验发布状态，保证
删除事件延迟期间旧向量也无法被注入模型。

- [ ] **Step 5: 测试并提交**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryIndexWorkerTest,PgVectorLongTermMemoryPortTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

```powershell
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/LongTermMemoryPort.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory
git commit -m "feat: 使用Outbox同步Agent长期记忆向量索引"
```

### Task 7: 实现混合检索与渐进式正文取回

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/AgentMemoryCatalogPort.java`
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogService.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/SearchAgentMemoryTool.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/GetAgentMemoryTool.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActToolAllowlistPolicy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/subagent/SubagentExecutionService.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogServiceTest.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/AgentMemoryToolIsolationTest.java`

**Interfaces:**
- Consumes: 正式卡片 DAO、`LongTermMemoryPort.retrieve`、`ReActToolContext.agentId`。
- Produces: `search(agentId, query, limit)` 轻量索引和 `getPublished(agentId, memoryIds)` 正文。

- [ ] **Step 1: 写跨 Agent 隔离与数量限制失败测试**

```java
@Test
void searchReturnsAtMostFivePublishedIndexesForCurrentAgent() {
    List<MemoryIndexItem> result = service.search("inventory", "下单后库存不一致", 20);
    assertTrue(result.size() <= 5);
    assertTrue(result.stream().allMatch(item -> item.agentId().equals("inventory")));
}

@Test
void getRejectsMemoryOwnedByAnotherAgent() {
    assertTrue(service.getPublished("inventory", List.of("ops-memory-1")).isEmpty());
}
```

- [ ] **Step 2: 写工具返回契约失败测试**

```java
@Test
void searchToolReturnsIndexWithoutFullContent() {
    String result = searchTool.search("库存不一致");
    assertTrue(result.contains("memoryId"));
    assertFalse(result.contains("contentJson"));
}

@Test
void getToolReadsAtMostThreePublishedMemories() {
    String result = getTool.get("[\"mem-1\",\"mem-2\",\"mem-3\",\"mem-4\"]");
    assertEquals(3, JSON.parseArray(result).size());
}
```

- [ ] **Step 3: 运行测试确认失败**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger,ai-agent-station-study-app -am -Dtest=AgentMemoryCatalogServiceTest,AgentMemoryToolIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL。

- [ ] **Step 4: 实现混合召回和 MySQL 回源**

先由 pgvector 取候选 ID，再合并 MySQL `title/description/memory_key` 关键词结果；服务端重新查询
`PUBLISHED` 卡片，丢弃跨 Agent、过期和旧版本记录。排序权重固定为：语义相关度 55%、关键词匹配
20%、证据完整度 15%、新鲜度 10%。无 pgvector 时只用 MySQL 检索。

- [ ] **Step 5: 装配两个核心记忆工具**

在白名单增加：

```java
public static final String SEARCH_AGENT_MEMORY = "search_agent_memory";
public static final String GET_AGENT_MEMORY = "get_agent_memory";
```

像 `retrieve_tool_call` 一样由运行时自动提供给启用长期记忆的 Agent，但工具内部始终从
`ReActToolContext` 获取 `agentId`，模型参数中不暴露可伪造的 `agentId`。

- [ ] **Step 6: 测试并提交**

Run: 使用 Step 3 命令，Expected: PASS。

```powershell
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/AgentMemoryCatalogPort.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogService.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogServiceTest.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/AgentMemoryToolIsolationTest.java
git commit -m "feat: 实现Agent长期记忆渐进式召回"
```

### Task 8: 增加审核 API 与端到端验证

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/http/AgentMemoryOperationsControllerTest.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/InventoryAgentLongTermMemoryFlowIT.java`
- Modify: `docs/architecture-overview.md`
- Modify: `docs/inventory-feedback-agent-quickstart.md`

**Interfaces:**
- Consumes: Task 3 审核发布服务、Task 7 检索服务。
- Produces: 候选列表、审核、发布、退役、索引搜索和正文读取 HTTP API。

- [ ] **Step 1: 写 Controller 失败测试**

```java
mockMvc.perform(post("/api/agents/inventory/memory/candidates/candidate-1/approve")
        .contentType(APPLICATION_JSON)
        .content("{\"reviewedBy\":\"developer\",\"comment\":\"规则已确认\"}"))
        .andExpect(status().isOk());

mockMvc.perform(get("/api/agents/inventory/memories/search").param("query", "库存不一致"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].contentJson").doesNotExist());
```

- [ ] **Step 2: 实现最小审核 API**

新增接口：

```text
GET  /api/agents/{agentId}/memory/candidates
POST /api/agents/{agentId}/memory/candidates/{candidateId}/approve
POST /api/agents/{agentId}/memory/candidates/{candidateId}/reject
POST /api/agents/{agentId}/memory/candidates/{candidateId}/publish
POST /api/agents/{agentId}/memories/{memoryId}/retire
GET  /api/agents/{agentId}/memories/search?query=...
POST /api/agents/{agentId}/memories/content
```

所有写接口要求审核人和中文理由非空，并校验 path 中 `agentId` 与数据归属一致。

- [ ] **Step 3: 编写库存 Agent 端到端集成测试**

测试固定链路：库存 MCP Feedback → 候选 Case → 人工审核 → `RESOLVED` → 长期记忆候选 →
批准发布 → Outbox 索引 → 新会话搜索轻量索引 → 按 ID 取回正文。额外断言未审核 Feedback、
单字消息和 MCP 超时不出现在正式记忆中。

- [ ] **Step 4: 运行全量验证**

Run:

```powershell
mvn -pl ai-agent-station-study-trigger,ai-agent-station-study-app -am test
mvn -DskipTests package
```

Expected: all tests PASS and BUILD SUCCESS。

- [ ] **Step 5: 更新架构文档并提交**

文档必须明确：短期摘要不进入长期记忆；MySQL 是权威数据；pgvector 是索引；长期记忆按 Agent
隔离；只有人工确认规则和已解决 Case 可以发布；模型使用“搜索索引 → 按 ID 取正文”。

```powershell
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/http/AgentMemoryOperationsControllerTest.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/InventoryAgentLongTermMemoryFlowIT.java docs/architecture-overview.md docs/inventory-feedback-agent-quickstart.md
git commit -m "feat: 完成长记忆审核召回闭环与库存场景验证"
```

## Final Verification

- [ ] 搜索 `SESSION_SUMMARY`，确认不存在写入 `LongTermMemoryPort` 的运行逻辑。
- [ ] 搜索 `queryByAgent`，确认长期召回不读取 `memory_summary`。
- [ ] 检查五张新表均包含 `agent_id` 索引和审计字段。
- [ ] 验证 Case 非 `RESOLVED` 时无法发布卡片。
- [ ] 验证两个 Agent 使用同一查询文本时只能看到各自卡片。
- [ ] 停止 PostgreSQL 后验证 MySQL 关键词召回仍可用。
- [ ] 模拟 embedding 429/503，确认 Outbox 重试且不阻塞对话。
- [ ] 运行 `mvn test` 与 `mvn -DskipTests package` 并记录结果。
