# 记忆折叠与可追溯取回 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前会话历史升级为“数据库完整留存、推理前多级确定性折叠、按 `tool_call_id` 可取回原件”的记忆闭环，保证长对话不超出模型上下文且工具调用消息始终合法。

**Architecture:** MySQL `chat_message` 是不可变事实源，保存 user、assistant、tool 三类消息及工具元数据。每次调用模型前，历史适配器将数据库记录还原为带有 `tool_calls`/`tool_call_id` 的内存消息副本，经过 sanitize、轮内折叠、轮间剥离、单条截断和最终预算裁剪后再发送给模型；折叠只修改副本，不修改数据库。折叠后的工具结果保留调用 ID 指针，`retrieve_tool_call` 按会话和调用 ID 从运行态或 MySQL 取回原文。

**Tech Stack:** Java 21、Spring AI 1.0.0、Spring Boot、MyBatis、MySQL、JUnit 5/JUnit 4（沿用现有模块测试约定）。

## Global Constraints

- `chat_message` 中的原始消息永不因折叠而删除或覆盖。
- 折叠管线只处理发给 LLM 的内存副本，不调用 LLM，不依赖模型生成 ID。
- `tool_call_id`、工具名称、调用参数与工具结果的配对必须经过每一级前后校验。
- 当前用户轮次永不删除；删除历史步骤时必须同时处理对应的 `assistant.tool_calls` 与 `tool` 回执。
- `retrieve_tool_call` 必须按 `sessionId + toolCallId` 查询，禁止仅凭全局调用 ID 返回其他会话数据。
- 普通会话滚动摘要与工具结果折叠分开：摘要用于恢复语义状态，折叠用于控制推理上下文大小。
- 失败采用可观测的降级：无法取回时返回明确错误，不重复执行原 MCP/工具。

---

### Task 1: 扩展历史消息模型并建立会话隔离的工具交换查询

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/HistoryMessage.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ChatMessageRecorder.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ToolCallExchange.java`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IChatMessageDao.java`
- Modify: `ai-agent-station-study-app/src/main/resources/mybatis/mapper/chat_message_mapper.xml`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/HistoryMessageMappingTest.java`

**Interfaces:**
- `HistoryMessage` 新增 `toolCallId`、`toolName`、`toolArguments`、`toolCallsJson` 字段，并保留 `role/content`。
- `ToolCallExchange` 统一承载 `sessionId/toolCallId/toolName/toolArguments/assistantContent/resultContent`。
- `ChatMessageRecorder` 新增 `ToolCallExchange findToolExchange(String sessionId, String toolCallId)`；旧 `findByToolCallId` 仅作为兼容方法，内部不得绕过会话校验。
- `IChatMessageDao` 新增 `queryBySessionAndToolCallId(sessionId, toolCallId)`。

- [ ] **Step 1: Write the failing test**

```java
@Test
void historyMessageCarriesToolPairMetadata() {
    HistoryMessage message = HistoryMessage.builder()
            .role("assistant")
            .content("准备查询库存反馈")
            .toolCallId("call_1")
            .toolName("query_feedback")
            .toolArguments("{\"date\":\"today\"}")
            .toolCallsJson("[{\"id\":\"call_1\"}]")
            .build();

    assertEquals("call_1", message.getToolCallId());
    assertEquals("query_feedback", message.getToolName());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-app -Dtest=HistoryMessageMappingTest test`

Expected: FAIL because `HistoryMessage` does not expose tool metadata.

- [ ] **Step 3: Add the metadata fields and DAO contract**

Use the existing Lombok builder and add these fields:

```java
private String toolCallId;
private String toolName;
private String toolArguments;
private String toolCallsJson;
```

Create the immutable exchange contract used by the retrieval tool:

```java
public record ToolCallExchange(
        String sessionId,
        String toolCallId,
        String toolName,
        String toolArguments,
        String assistantContent,
        String resultContent) {}
```

Add the mapper query:

```xml
<select id="queryBySessionAndToolCallId" resultMap="ChatMessageMap">
    SELECT * FROM chat_message
    WHERE session_id = #{sessionId} AND tool_call_id = #{toolCallId}
    ORDER BY id ASC LIMIT 1
</select>
```

- [ ] **Step 4: Run the focused test**

Run: `mvn -pl ai-agent-station-study-app -Dtest=HistoryMessageMappingTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/HistoryMessage.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ChatMessageRecorder.java ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IChatMessageDao.java ai-agent-station-study-app/src/main/resources/mybatis/mapper/chat_message_mapper.xml ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/HistoryMessageMappingTest.java
git commit -m "feat: 扩展会话工具消息元数据与会话级取回查询"
```

### Task 2: 实现可验证的多级折叠与指针生成

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemoryFoldingPipeline.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/HistoryMessageSanitizer.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/FoldedToolReference.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/MemoryFoldingPipelineTest.java`

**Interfaces:**
- `HistoryMessageSanitizer.sanitize(List<Map<String,Object>>)` 清理孤立 tool 和不完整 tool_calls。
- `MemoryFoldingPipeline.fold(List<Map<String,Object>> messages, FoldConfig config)` 返回新的可变副本，不修改输入列表。
- `FoldedToolReference.render(String toolName, String toolCallId, String content)` 生成固定格式的取回指针。

- [ ] **Step 1: Write the failing tests**

```java
@Test
void foldsOldToolResultButKeepsRetrievalPointer() {
    List<Map<String, Object>> input = List.of(
            Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                    "id", "call_old", "type", "function",
                    "function", Map.of("name", "query_feedback", "arguments", "{\"date\":\"today\"}")))),
            Map.of("role", "tool", "tool_call_id", "call_old", "name", "query_feedback",
                    "content", "x".repeat(1000)),
            Map.of("role", "user", "content", "继续分析")
    );

    List<Map<String, Object>> output = MemoryFoldingPipeline.fold(input, FoldConfig.testProfile());

    String toolContent = String.valueOf(output.get(1).get("content"));
    assertTrue(toolContent.contains("call_old"));
    assertTrue(toolContent.contains("retrieve_tool_call"));
}

@Test
void sanitizeDropsOrphanToolAndIncompleteAssistantPair() {
    List<Map<String, Object>> output = HistoryMessageSanitizer.sanitize(List.of(
            Map.of("role", "tool", "tool_call_id", "missing", "content", "orphan"),
            Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call_missing")))
    );

    assertTrue(output.stream().noneMatch(message -> "tool".equals(message.get("role"))));
    assertTrue(output.stream().noneMatch(message -> message.containsKey("tool_calls")));
}
```

- [ ] **Step 2: Run the focused test to verify failure**

Run: `mvn -pl ai-agent-station-study-app -Dtest=MemoryFoldingPipelineTest test`

Expected: FAIL because the existing pipeline does not receive a fold configuration and does not guarantee an explicit pointer for every folded tool result.

- [ ] **Step 3: Implement the deterministic folding stages**

Implement the following order in `fold`:

```text
copy -> sanitize -> fold current-round steps -> strip old rounds
     -> cap each message -> final budget trim -> sanitize
```

The default configuration must be explicit:

```java
FoldConfig.defaultProfile() = new FoldConfig(
        6,      // recent tool steps kept in full
        12,     // older than this can be summarized
        20_000, // single-message character cap
        40_000, // history level-1 budget
        80_000, // history level-2 budget
        120_000 // final safety trigger
);
```

For a folded tool result, preserve `tool_call_id`, `name`, the first error line or key path hint, and append a pointer generated by `FoldedToolReference`.

- [ ] **Step 4: Verify message-pair invariants**

Add a helper assertion used by tests:

```java
static void assertValidToolPairs(List<Map<String, Object>> messages) {
    Set<String> pending = new HashSet<>();
    for (Map<String, Object> message : messages) {
        if ("assistant".equals(message.get("role"))) {
            Object calls = message.get("tool_calls");
            if (calls instanceof List<?> list) {
                list.forEach(call -> pending.add(String.valueOf(((Map<?, ?>) call).get("id"))));
            }
        }
        if ("tool".equals(message.get("role"))) {
            assertTrue(pending.remove(String.valueOf(message.get("tool_call_id"))));
        }
    }
    assertTrue(pending.isEmpty());
}
```

- [ ] **Step 5: Run tests and commit**

Run: `mvn -pl ai-agent-station-study-app -Dtest=MemoryFoldingPipelineTest test`

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemoryFoldingPipeline.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/HistoryMessageSanitizer.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/FoldedToolReference.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/MemoryFoldingPipelineTest.java
git commit -m "feat: 增加多级会话折叠与工具取回指针"
```

### Task 3: 从数据库完整还原工具消息并接入两条执行策略

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/chat/ChatExecuteStrategy.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/HistoryMessageMapper.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/HistoryMessageMapperTest.java`

**Interfaces:**
- `ChatMessageRecorderDb.getHistory` 返回 user、assistant、tool 三类历史消息，摘要仍作为单独的 assistant 状态消息注入。
- `HistoryMessageMapper.toSpringMessages(List<Map<String,Object>>)` 将折叠后的结构还原为 Spring AI 的 `UserMessage`、`AssistantMessage` 和 `ToolResponseMessage`。

- [ ] **Step 1: Write the failing mapper test**

```java
@Test
void mapsAssistantToolCallAndToolResponseWithoutDroppingIds() {
    List<Map<String, Object>> messages = List.of(
            Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                    "id", "call_1", "type", "function",
                    "function", Map.of("name", "query_feedback", "arguments", "{}")))),
            Map.of("role", "tool", "tool_call_id", "call_1", "name", "query_feedback", "content", "ok")
    );

    List<Message> mapped = HistoryMessageMapper.toSpringMessages(messages);

    assertTrue(mapped.get(0) instanceof AssistantMessage);
    assertEquals("call_1", ((AssistantMessage) mapped.get(0)).getToolCalls().get(0).id());
    assertTrue(mapped.get(1) instanceof ToolResponseMessage);
}
```

- [ ] **Step 2: Run the test and confirm current failure**

Run: `mvn -pl ai-agent-station-study-app -Dtest=HistoryMessageMapperTest test`

Expected: FAIL because both execution strategies currently convert history to only user/assistant text and discard tool metadata.

- [ ] **Step 3: Return full history metadata from `ChatMessageRecorderDb`**

For each `ChatMessage` after the active summary coverage cursor, map all four metadata fields into `HistoryMessage`. Do not filter out `role=tool`; the mapper will enforce pairing after folding.

- [ ] **Step 4: Replace duplicated message conversion**

In both `ReActExecuteStrategy` and `ChatExecuteStrategy`, replace manual construction such as:

```java
maps.add(Map.of("role", h.getRole(), "content", h.getContent()));
```

with a shared mapper that copies `toolCallId`, `toolName`, `toolArguments`, and `toolCallsJson`, appends the current user message, runs `MemoryFoldingPipeline.fold`, and converts the result to Spring AI message objects.

- [ ] **Step 5: Run focused and regression tests**

Run:

```text
mvn -pl ai-agent-station-study-app -Dtest=HistoryMessageMapperTest,RollingSummaryPolicyTest,ReActGuardrailTest test
```

Expected: PASS, with no orphan tool messages in the generated prompt.

- [ ] **Step 6: Commit**

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/chat/ChatExecuteStrategy.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/HistoryMessageMapper.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/HistoryMessageMapperTest.java
git commit -m "fix: 保留工具消息配对并接入会话折叠管线"
```

### Task 4: 完成按会话取回工具原文的闭环

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/RetrieveToolCallTool.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ChatMessageRecorderNoop.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RetrieveToolCallToolTest.java`

**Interfaces:**
- `RetrieveToolCallTool` 从当前 `ReActToolContext` 获取 session ID，并调用 `findToolExchange(sessionId, toolCallId)`。
- 返回结构包含 `toolCallId`、`source`、工具名称、原始参数、结果内容、`truncated` 和 `originalChars`。

- [ ] **Step 1: Write security and recovery tests**

```java
@Test
void retrievesOnlyFromCurrentSession() {
    when(recorder.findToolExchange("session_a", "call_1")).thenReturn(exchange("session_a", "call_1"));
    when(recorder.findToolExchange("session_b", "call_1")).thenReturn(null);

    assertTrue(tool.retrieveToolCall("session_b", "call_1").contains("no tool call exchange"));
}

@Test
void doesNotExecuteOriginalToolAgain() {
    when(recorder.findToolExchange("session_a", "call_1")).thenReturn(exchange("session_a", "call_1"));

    tool.retrieveToolCall("session_a", "call_1");

    verify(recorder).findToolExchange("session_a", "call_1");
    verifyNoInteractions(originalMcpCallback);
}
```

- [ ] **Step 2: Implement session-scoped lookup and bounded response**

Query the in-memory exchange first when available, then MySQL by session and call ID. Expose `retrieveToolCall(String sessionId, String toolCallId)` for the service-level test adapter and keep the Spring AI `@Tool` entrypoint delegating to it. If the response exceeds the configured per-message limit, return the bounded content plus `truncated=true` and `originalChars`.

- [ ] **Step 3: Add observability events**

Emit trace events for `FOLD_POINTER_CREATED`, `RETRIEVE_REQUESTED`, `RETRIEVE_HIT`, `RETRIEVE_MISS`, and `RETRIEVE_TRUNCATED`, including agent ID, session ID, tool call ID, source and duration.

- [ ] **Step 4: Run tests and commit**

Run: `mvn -pl ai-agent-station-study-app -Dtest=RetrieveToolCallToolTest test`

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/memory/RetrieveToolCallTool.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ChatMessageRecorderNoop.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RetrieveToolCallToolTest.java
git commit -m "feat: 完成工具结果按会话取回闭环"
```

### Task 5: 持久化滚动摘要与折叠游标的恢复验证

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java`
- Modify: `ai-agent-station-study-app/src/main/resources/application-dev.yml`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/ShortTermMemoryServiceTest.java`

**Interfaces:**
- `MemorySummary` 保存 `version/startMessageId/endMessageId/status/modelId`。
- 下一轮历史组装只读取 ACTIVE 摘要和 `id > endMessageId` 的近期原始消息。

- [ ] **Step 1: Write the persistence tests**

```java
@Test
void refreshUsesCoveredCursorAndDoesNotSummarizeSameMessagesTwice() {
    when(summaryDao.queryLatest("s1")).thenReturn(activeSummary(2));
    when(messageDao.queryBySessionId("s1")).thenReturn(messages(1, 2, 3, 4));

    service.refreshIfNeeded("inventory", "s1", "deepseek-v4-flash");

    verify(summaryDao).queryLatest("s1");
    verify(summaryDao, never()).insert(argThat(summary -> summary.getEndMessageId() <= 2));
}
```

- [ ] **Step 2: Verify configuration defaults**

Keep development defaults explicit and adjustable:

```yaml
agent:
  memory:
    summary-token-threshold: 8000
    summary-hard-limit: 16000
    retain-messages: 24
    min-new-user-turns: 4
```

- [ ] **Step 3: Make summary state update atomic**

Wrap `supersede + insert summary + insert state` in one transaction so a failed state insert cannot leave two active summaries or a summary without structured state.

- [ ] **Step 4: Run memory tests and commit**

Run: `mvn -pl ai-agent-station-study-app -Dtest=ShortTermMemoryServiceTest,RollingSummaryPolicyTest,LongTermMemoryRecallServiceTest test`

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java ai-agent-station-study-app/src/main/resources/application-dev.yml ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/ShortTermMemoryServiceTest.java
git commit -m "fix: 持久化滚动摘要覆盖游标并保证恢复一致性"
```

### Task 6: 端到端回归与运行说明

**Files:**
- Create: `docs/memory-folding.md`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/EnterpriseSchemaContractTest.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/MemoryFoldingContractTest.java`

- [ ] **Step 1: Add the contract scenario**

The scenario must cover: 10 tool calls, one tool failure, history size above the fold threshold, pointer generation, `retrieve_tool_call`, and a second LLM request that receives the recovered result without re-executing the original tool.

- [ ] **Step 2: Run module regression**

Run:

```text
mvn -pl ai-agent-station-study-app -am test
```

Expected: all existing memory, ReAct guardrail, MCP and conversation continuity tests pass.

- [ ] **Step 3: Document the data flow**

Document this invariant flow:

```text
chat_message 原文
    -> HistoryMessageMapper
    -> sanitize
    -> 轮内折叠
    -> 轮间剥离
    -> 单条截断
    -> 最终预算裁剪
    -> LLM
    -> retrieve_tool_call(toolCallId)
    -> 当前会话继续推理
```

- [ ] **Step 4: Commit the final verification**

```bash
git add docs/memory-folding.md ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/EnterpriseSchemaContractTest.java ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/MemoryFoldingContractTest.java
git commit -m "test: 增加记忆折叠与工具取回端到端契约"
```

## Verification Checklist

- [ ] 数据库原始消息未被折叠逻辑修改。
- [ ] 每次发给 LLM 的消息都通过 `sanitize`，不存在孤立 `tool` 或未闭合 `tool_calls`。
- [ ] 折叠后的工具结果包含稳定的 `tool_call_id` 指针。
- [ ] 取回工具按 `sessionId + toolCallId` 隔离数据。
- [ ] 取回只读取历史原文，不重新执行原工具。
- [ ] 滚动摘要按覆盖游标增量折叠，服务重启后可恢复。
- [ ] 折叠、取回命中、取回失败和截断均进入运行轨迹。
- [ ] 全量 Maven 测试通过后，才在简历中描述为完整闭环。
