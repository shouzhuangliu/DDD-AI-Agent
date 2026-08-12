# Agent 自动长期记忆 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Agent 长期记忆改造成按 Agent 自动新增、更新、软删除和三级召回的业务知识系统。

**Architecture:** MySQL 的记忆卡片为权威状态，新增操作审计表记录每次 CREATE、UPDATE、RETIRE；异步提取器与主 Agent 工具共享同一个 upsert/retire 服务。召回先固定注入关键卡片，再并行 MySQL/pgvector 粗召回，由轻量模型从候选 memoryId 中精排，最后按 ID 延迟加载正文。

**Tech Stack:** Java 21、Spring Boot、Spring AI Tool、MyBatis、MySQL、Redis、pgvector、JUnit 5、Mockito。

## Global Constraints

- 记忆仅按 `agentId` 隔离；不保存用户偏好、不引入主题。
- Case 工作流仍使用人工审核；长期记忆不再存在候选审核/发布状态机。
- 记忆删除必须是软删除；MySQL 卡片是权威状态，pgvector 仅为可重建索引。
- 召回失败、向量库失败或精排模型失败不得阻断主对话。
- 当前请求最多加载五条相关记忆，完整正文只能按已验证 `memoryId` 延迟读取。

---

### Task 1: 自动长期记忆的数据模型与审计

**Files:**
- Create: `ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260813__agent_memory_auto_governance.sql`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryCard.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AgentMemoryChangeLog.java`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IAgentMemoryCardDao.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IAgentMemoryChangeLogDao.java`
- Modify: `ai-agent-station-study-app/src/main/resources/mybatis/mapper/agent_memory_card_mapper.xml`
- Create: `ai-agent-station-study-app/src/main/resources/mybatis/mapper/agent_memory_change_log_mapper.xml`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/AgentMemoryAutoGovernanceSchemaTest.java`

**Interfaces:**
- Produces `AgentMemoryCard.isDeleted/importance/pinned/updatedReason` and `AgentMemoryChangeLog`.
- Produces DAO operations `queryActiveByIdentity(agentId,memoryType,memoryKey)` and `softDelete(agentId,memoryId,reason)`.

- [ ] **Step 1: Write the failing schema contract test**

```java
@Test
void autoGovernanceMigrationDefinesSoftDeleteAndChangeAudit() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/sql/mysql/migrations/V20260813__agent_memory_auto_governance.sql"));
    assertTrue(sql.contains("is_deleted"));
    assertTrue(sql.contains("importance"));
    assertTrue(sql.contains("pinned"));
    assertTrue(sql.contains("agent_memory_change_log"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl ai-agent-station-study-app -Dtest=AgentMemoryAutoGovernanceSchemaTest test`

Expected: FAIL because migration does not exist.

- [ ] **Step 3: Add migration, PO and DAO contracts**

```sql
ALTER TABLE agent_memory_card
  ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN importance INT NOT NULL DEFAULT 50,
  ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN updated_reason VARCHAR(1000) NOT NULL DEFAULT '';

CREATE TABLE agent_memory_change_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  change_id VARCHAR(64) NOT NULL,
  agent_id VARCHAR(64) NOT NULL,
  memory_id VARCHAR(64) NOT NULL,
  memory_version INT NOT NULL,
  operation VARCHAR(16) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  source_type VARCHAR(24) NOT NULL,
  source_id VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_memory_change_id (change_id)
);
```

- [ ] **Step 4: Run the schema test to verify it passes**

Run: `mvn -pl ai-agent-station-study-app -Dtest=AgentMemoryAutoGovernanceSchemaTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-station-study-app ai-agent-station-study-infrastructure
git commit -m "feat: 增加Agent自动长期记忆审计模型"
```

### Task 2: 受控的自动创建、更新与软删除服务

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryLifecycleService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryIndexWorker.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryLifecycleServiceTest.java`

**Interfaces:**
- Produces `upsert(UpsertCommand)` and `retire(RetireCommand)`.
- `UpsertCommand` contains `agentId,memoryType,memoryKey,title,description,content,importance,pinned,sourceType,sourceId,evidenceQuote,reason`.
- `RetireCommand` contains `agentId,memoryId,sourceType,sourceId,evidenceQuote,reason`.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void sameIdentityUpdatesStableMemoryIdAndIncrementsVersion() {
    when(cardDao.queryActiveByIdentity("inventory", "BUSINESS_RULE", "inventory.stock-threshold"))
        .thenReturn(existing("mem-1", 2));
    AgentMemoryLifecycleService.Result result = service.upsert(command("库存差异阈值为 2%"));
    assertEquals("mem-1", result.memoryId());
    assertEquals(3, result.version());
    assertEquals("UPDATE", result.operation());
}

@Test
void retireSoftDeletesCardAndQueuesVectorDelete() {
    when(cardDao.queryActiveByMemoryId("inventory", "mem-1")).thenReturn(existing("mem-1", 2));
    service.retire(retireCommand());
    verify(cardDao).softDelete("inventory", "mem-1", "旧阈值已被新规则推翻");
    verify(outboxDao).insert(argThat(event -> "DELETE".equals(event.getEventType())));
}
```

- [ ] **Step 2: Run lifecycle tests to verify they fail**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because lifecycle service is absent.

- [ ] **Step 3: Implement the lifecycle service**

```java
public Result upsert(UpsertCommand command) {
    validateEvidence(command.agentId(), command.sourceType(), command.sourceId(), command.evidenceQuote());
    AgentMemoryCard active = cardDao.queryActiveByIdentity(...);
    int nextVersion = active == null ? 1 : active.getVersion() + 1;
    String memoryId = active == null ? UUID.randomUUID().toString() : active.getMemoryId();
    // active version is superseded; new version becomes active; write UPSERT and old DELETE outbox events.
    return new Result(memoryId, nextVersion, active == null ? "CREATE" : "UPDATE");
}
```

- [ ] **Step 4: Run lifecycle tests to verify they pass**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryLifecycleServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-station-study-trigger ai-agent-station-study-infrastructure ai-agent-station-study-app
git commit -m "feat: 实现Agent长期记忆自动增改与软删除"
```

### Task 3: 主 Agent 主动记忆工具和异步提取改造

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/AgentMemoryLifecycleTool.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActToolAllowlistPolicy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/MemoryCandidateModelClient.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/SpringMemoryCandidateModelClient.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractor.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/ConversationMemoryCandidateExtractorTest.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryToolsTest.java`

**Interfaces:**
- `AgentMemoryLifecycleTool.upsertAgentMemory(...)` delegates to `AgentMemoryLifecycleService.upsert`.
- `MemoryCandidateModelClient.Extraction` includes `operation,targetMemoryId` and supports `CREATE/UPDATE/RETIRE/NOOP`.

- [ ] **Step 1: Write failing tests for explicit tool upsert and extractor UPDATE**

```java
@Test
void extractorAppliesUpdateInsteadOfCreatingDuplicateCandidate() {
    when(modelClient.extract(any())).thenReturn(extraction("UPDATE", "mem-1"));
    extractor.extractIfEligible("inventory", "s-1", "model");
    verify(lifecycleService).upsert(any(AgentMemoryLifecycleService.UpsertCommand.class));
    verify(candidateService, never()).submitCandidate(any());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=ConversationMemoryCandidateExtractorTest,AgentMemoryToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because extraction still uses candidate review flow.

- [ ] **Step 3: Implement tool wiring and extraction operations**

```java
switch (extraction.operation()) {
  case "CREATE", "UPDATE" -> lifecycleService.upsert(toUpsertCommand(...));
  case "RETIRE" -> lifecycleService.retire(toRetireCommand(...));
  case "NOOP" -> { }
  default -> throw new IllegalArgumentException("unsupported memory operation");
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=ConversationMemoryCandidateExtractorTest,AgentMemoryToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-station-study-domain ai-agent-station-study-trigger
git commit -m "feat: 接入Agent主动与异步长期记忆学习"
```

### Task 4: 三级召回和请求级一次性注入

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/MemoryRelevanceReranker.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogService.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/AgentMemoryCatalogPort.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/AgentMemoryCatalogServiceTest.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/MemoryRelevanceRerankerTest.java`

**Interfaces:**
- `searchCandidates(agentId,query,20)` merges MySQL and vector candidates without content.
- `rerank(query, toolSummary, skillSummary, candidates, 5)` returns only candidate memory IDs.
- `getPublished(agentId, memoryIds)` is the authoritative delayed content loader.

- [ ] **Step 1: Write failing recall tests**

```java
@Test
void rerankerOutputIsIntersectedWithCandidateMemoryIds() {
    when(reranker.rerank(any(), any(), any(), anyList(), eq(5))).thenReturn(List.of("mem-1", "forged"));
    List<MemoryContent> content = service.recall("inventory", "查询库存阈值", "", "");
    assertEquals(List.of("mem-1"), content.stream().map(MemoryContent::memoryId).toList());
}

@Test
void vectorFailureFallsBackToMysqlCandidates() {
    when(indexPort.searchIndex(anyString(), anyString(), anyInt())).thenThrow(new RuntimeException("pg unavailable"));
    assertFalse(service.searchCandidates("inventory", "库存", 20).isEmpty());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryCatalogServiceTest,MemoryRelevanceRerankerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because candidate search and reranking API are absent.

- [ ] **Step 3: Implement coarse retrieval, whitelist reranking and delayed loading**

```java
List<String> candidateIds = candidates.stream().map(MemoryIndexItem::memoryId).toList();
List<String> selected = reranker.rerank(query, toolSummary, skillSummary, candidates, 5).stream()
    .filter(candidateIds::contains).distinct().limit(5).toList();
return getPublished(agentId, selected);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryCatalogServiceTest,MemoryRelevanceRerankerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-station-study-domain ai-agent-station-study-trigger ai-agent-station-study-infrastructure ai-agent-station-study-app
git commit -m "feat: 实现Agent长期记忆三级召回"
```

### Task 5: 移除旧审核 API、部署迁移和全量回归

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentMemoryOperationsController.java`
- Modify: `compose.local.yml`
- Modify: `README.md`
- Modify: `docs/architecture-overview.md`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/http/AgentMemoryOperationsControllerTest.java`

- [ ] **Step 1: Write failing controller tests for auto-memory endpoints**

```java
@Test
void retiredMemoryIsNotReturnedByContentEndpoint() throws Exception {
    when(catalog.getPublished("inventory", List.of("retired"))).thenReturn(List.of());
    mvc.perform(post("/api/v1/agents/inventory/memory/content")
            .contentType(APPLICATION_JSON).content("{\"memoryIds\":[\"retired\"]}"))
       .andExpect(status().isOk()).andExpect(content().json("[]"));
}
```

- [ ] **Step 2: Run controller test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=AgentMemoryOperationsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL until old candidate approve/reject/publish endpoints are removed or replaced.

- [ ] **Step 3: Replace controller endpoints and document local upgrade**

```text
GET  /memory/index
POST /memory/content
POST /memory/{memoryId}/retire
GET  /memory/audit
```

Document that existing local database volumes must execute `scripts/prepare-native-mysql.ps1`; add `V20260813` to Compose fresh initialization.

- [ ] **Step 4: Run full verification**

Run: `docker compose -f compose.local.yml config --quiet`

Run: `mvn test`

Run: `mvn -DskipTests package`

Expected: all commands exit 0.

- [ ] **Step 5: Commit and push**

```powershell
git add README.md compose.local.yml docs ai-agent-station-study-trigger
git commit -m "feat: 完成长周期业务记忆自动治理闭环"
git push origin main
```

## Plan Self-Review

- Spec coverage: Tasks 1-3 cover automatic data lifecycle, tools, asynchronous extraction and audit. Task 4 covers fixed/coarse/reranked delayed recall and fallback. Task 5 covers endpoint replacement, migration deployment, documentation and full verification.
- Placeholder scan: no unresolved placeholders, TODO markers or undefined execution steps remain.
- Type consistency: lifecycle commands, catalog candidate IDs and extraction operation names are defined before downstream tasks consume them.
