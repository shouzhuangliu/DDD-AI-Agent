# MCP 渐进式 Schema 披露 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 ReAct Agent 在调用业务 MCP 前先按会话读取对应工具的完整 Schema，并通过会话门禁、参数校验和运行观察保证渐进式披露链路可追踪、可隔离。

**Architecture:** 在现有 `call_mcp_tool` 统一入口旁增加 `get_mcp_tool_schema` 内部 Tool；运行时从当前 Agent 绑定的 `McpSyncClient` 执行 `tools/list`，将选中的 `McpSchema.Tool` 缓存到 `ReActToolContext`。调用入口只允许使用本会话已加载的 Schema，并复用同一工具对象校验参数后发出 MCP `tools/call`。ReAct 系统提示词只注入摘要和两阶段规则，SSE/消息记录增加 Schema 观察事件。

**Tech Stack:** Java 17、Spring Boot、Spring AI Tool、Spring AI MCP Client、MCP JSON-RPC、JUnit 5、Mockito、Fastjson2。

## Global Constraints

- 保留 `call_mcp_tool(mcpId, toolName, args)` 对现有 Agent 的兼容性。
- Schema 查询必须受当前 Agent 的 MCP 绑定和工具白名单限制。
- 未读取 Schema 时禁止跨进程发送 MCP `tools/call`。
- Schema 缓存只存在当前会话运行时，不新增数据库表，不写入长期记忆。
- 保留 stdio、SSE、Streamable HTTP 的 MCP Client 装配逻辑。
- MCP/工具异常只记录为运行观察，不直接作为 Feedback 或 Case 证据。
- 每个生产方法先有能失败的自动化测试，再写最小实现。

---

### Task 1: 建立会话级 Schema 状态契约

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContext.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContextSchemaTest.java`

**Interfaces:**
- Produces `rememberMcpToolSchema(String mcpId, String toolName, McpSchema.Tool tool)`、`getMcpToolSchema(String mcpId, String toolName)` 和 `hasMcpToolSchema(String mcpId, String toolName)`。
- 缓存键统一为 `mcpId + "\n" + toolName`，输入两端 trim，避免同一工具因空格重复缓存。

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldRememberAndReadSchemaOnlyForCurrentMcpAndTool() {
    ReActToolContext context = ReActToolContext.builder()
            .sessionId("session-1")
            .boundMcpIds(List.of("inventory-feedback-mcp"))
            .build();
    McpSchema.Tool tool = new McpSchema.Tool(
            "get_today_feedback", "查询今日反馈", null,
            Map.of("type", "object"), null, null);

    assertFalse(context.hasMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
    context.rememberMcpToolSchema(" inventory-feedback-mcp ", " get_today_feedback ", tool);
    assertTrue(context.hasMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
    assertSame(tool, context.getMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
    assertFalse(context.hasMcpToolSchema("other-mcp", "get_today_feedback"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=ReActToolContextSchemaTest test`

Expected: FAIL because `ReActToolContext` has no Schema cache methods.

- [ ] **Step 3: Write minimal implementation**

Add a session-owned `Map<String, McpSchema.Tool>` with a builder default and synchronized accessors. Return `null` for blank/missing keys; do not derive permissions from this map.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=ReActToolContextSchemaTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContext.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContextSchemaTest.java
git commit -m "test: 增加会话级MCP Schema缓存契约"
```

### Task 2: 增加 Schema 查询 Tool 与工具白名单

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActToolAllowlistPolicy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolSchemaTool.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolSchemaToolTest.java`

**Interfaces:**
- New allowlist ID: `get_mcp_tool_schema`.
- New Spring AI Tool method:

```java
String getMcpToolSchema(String mcpId, String toolName)
```

- The Tool reads the current `ReActToolContext`, resolves `McpSyncClient` from the configured MCP bean, calls `listTools()`, caches the matching `McpSchema.Tool`, and returns compact JSON containing `name`, `title`, `description`, `inputSchema`, and optional `outputSchema`.

- [ ] **Step 1: Write the failing test**

Add tests for pure validation helpers first:

```java
@Test
void shouldRejectSchemaLookupWhenMcpIsNotBound() {
    ReActToolContextHolder.set(ReActToolContext.builder()
            .sessionId("session-1")
            .boundMcpIds(List.of("inventory-feedback-mcp"))
            .build());
    String result = tool.getMcpToolSchema("other-mcp", "get_today_feedback");
    assertTrue(result.contains("MCP 未绑定"));
}

@Test
void shouldReturnSchemaAndRememberItWhenToolIsExposed() {
    when(applicationContext.getBean(anyString(), eq(McpSyncClient.class))).thenReturn(client);
    when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(exposedTool), null, null));
    String result = tool.getMcpToolSchema("inventory-feedback-mcp", "get_today_feedback");
    assertTrue(result.contains("get_today_feedback"));
    assertTrue(result.contains("inputSchema"));
    assertSame(exposedTool, ReActToolContextHolder.get()
            .getMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=McpToolSchemaToolTest test`

Expected: FAIL because the Tool class and allowlist entry do not exist.

- [ ] **Step 3: Write minimal implementation**

Implement `McpToolSchemaTool` using the existing bean naming convention `AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId)`. Reuse `McpCallTool.requiredArgumentsSummary` only for human-readable observation; return the complete Schema JSON. Emit `mcp_schema_requested`, `mcp_schema_loaded`, or `mcp_schema_rejected` observations through the existing `AbstractReActTool` helpers.

Add the allowlist option and expose the Tool in `ReActExecuteStrategy.selectToolObjects`. When an Agent has bound MCPs, `AgentRuntimeBindingService.resolveEffectiveToolIds` must add both `CALL_MCP_TOOL` and `GET_MCP_TOOL_SCHEMA`.

Update the system prompt to require Schema lookup after choosing a business MCP tool and before calling `call_mcp_tool`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=McpToolSchemaToolTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActToolAllowlistPolicy.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolSchemaTool.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolSchemaToolTest.java
git commit -m "feat: 增加MCP工具Schema渐进式披露"
```

### Task 3: 为真实 MCP 调用增加 Schema 门禁

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpCallTool.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpCallToolSchemaGateTest.java`

**Interfaces:**
- Before `McpSyncClient.callTool`, `McpCallTool` checks the current context cache for the effective `(mcpId, toolName)` pair.
- Missing cache returns `MCP_SCHEMA_REQUIRED` and does not invoke the client.
- Successful lookup reuses the cached `McpSchema.Tool` for required-argument validation, while still confirming the live tool name is exposed.

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldBlockCallBeforeSchemaLookup() {
    // configure a bound MCP and a client exposing get_today_feedback
    String result = tool.callMcpTool(
            "inventory-feedback-mcp", "get_today_feedback", "{}");
    assertTrue(result.contains("MCP_SCHEMA_REQUIRED"));
    verify(client, never()).callTool(any());
}

@Test
void shouldCallMcpAfterSchemaWasLoaded() {
    // put the exposed McpSchema.Tool in the context cache first
    String result = tool.callMcpTool(
            "inventory-feedback-mcp", "get_today_feedback", "{\"limit\":20}");
    verify(client).callTool(any());
    assertTrue(result.contains("feedback"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=McpCallToolSchemaGateTest test`

Expected: FAIL because calls currently proceed after only a live `tools/list` lookup.

- [ ] **Step 3: Write minimal implementation**

Add the cache check after resolving the effective MCP/tool (including the special `get_today_feedback` routing) and before `callTool`. Use the cached Schema for required-field validation. Preserve existing connection and tool-name errors. Return a Chinese message containing the stable machine code `MCP_SCHEMA_REQUIRED` and emit `mcp_call_blocked`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=McpCallToolSchemaGateTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpCallTool.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpCallToolSchemaGateTest.java
git commit -m "feat: 增加MCP调用前Schema门禁"
```

### Task 4: 完成回归验证与文档

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-mcp-progressive-schema-disclosure-design.md` only if implementation details require a clarified contract.
- Test: all existing tests under `ai-agent-station-study-trigger/src/test/java`.

- [ ] **Step 1: Run focused MCP and ReAct tests**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=McpToolSchemaToolTest,McpCallToolSchemaGateTest,AgentRuntimeBindingServiceTest,ChatAgentRoutePolicyTest test`

- [ ] **Step 2: Run the full Maven test suite**

Run: `mvn test`

Expected: exit code 0 and zero test failures.

- [ ] **Step 3: Inspect the diff and verify unrelated worktree files remain untouched**

Run: `git status --short; git diff --check; git diff HEAD~3 --stat`

- [ ] **Step 4: Commit any final implementation/documentation changes**

```bash
git add ai-agent-station-study-domain ai-agent-station-study-trigger docs/superpowers
git commit -m "test: 验证MCP渐进式Schema披露闭环"
```
