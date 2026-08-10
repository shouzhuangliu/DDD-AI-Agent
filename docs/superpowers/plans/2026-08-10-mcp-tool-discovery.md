# MCP 工具按需检索与句柄调用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让 Agent 只注入 MCP 服务摘要，通过 `discover_mcp_tools` 在绑定范围内检索最多 3 个候选工具并返回完整 Schema，再用会话级 `toolHandle` 调用真实 MCP。

**Architecture:** 在 `ReActToolContext` 中维护会话级工具 Handle；`McpToolDiscoveryTool` 负责读取绑定 MCP 的 tools/list、确定性评分和候选 Schema 返回；`McpToolHandleCallTool` 负责 Handle 解析和调用前校验，并复用现有 `McpCallTool` 的 MCP 执行逻辑。旧 Schema/旧参数入口保留但不再作为新 ReAct 流程的首选工具。

**Tech Stack:** Spring Boot、Spring AI `@Tool`、MCP SDK 0.7.0、Fastjson2、JUnit 5、Mockito、Maven 多模块 Reactor。

## Global Constraints

- 只检索当前 `ReActToolContext` 绑定的 MCP；未绑定 MCP 不得访问客户端。
- 每次发现最多返回 3 个候选，按匹配分降序、`mcpId + toolName` 升序稳定排序。
- Handle 必须绑定 Agent、会话、MCP、工具名、Schema 哈希和过期时间；跨会话、过期或 Schema 变化必须拒绝。
- 不新增数据库表，不改变 MCP Server 的 JSON-RPC 协议。
- 所有生产修改必须先有失败测试，再写最小实现；每个任务独立提交。
- 不提交工作区已有的 `docs/interview/`、`tmp/` 和简历 PDF。

---

### Task 1: 会话级 MCP 工具 Handle 状态

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContext.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContextSchemaTest.java`

**Interfaces:**
- Add `rememberMcpToolHandle(String handle, McpToolHandleBinding binding)`.
- Add `getMcpToolHandle(String handle)` and `removeMcpToolHandle(String handle)`.
- Add `McpToolHandleBinding` with `handle`, `agentId`, `sessionId`, `mcpId`, `toolName`, `schemaHash`, `expiresAtEpochMillis`, `McpSchema.Tool tool`.
- Add `isExpired(long now)` to the binding.

- [ ] **Step 1: Write the failing test**

```java
@Test
void storesHandleOnlyForTheCurrentConversation() {
    ReActToolContext context = ReActToolContext.builder()
            .agentId("inventory-agent").sessionId("session-1").build();
    ReActToolContext.McpToolHandleBinding binding = new ReActToolContext.McpToolHandleBinding(
            "handle-1", "inventory-agent", "session-1", "inventory-mcp",
            "get_today_feedback", "sha256:x", System.currentTimeMillis() + 60_000, tool);

    context.rememberMcpToolHandle("handle-1", binding);

    assertSame(binding, context.getMcpToolHandle("handle-1"));
    assertNull(context.getMcpToolHandle("missing"));
    assertFalse(binding.isExpired(System.currentTimeMillis()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=ReActToolContextSchemaTest' test`

Expected: FAIL because the Handle binding API does not exist.

- [ ] **Step 3: Write minimal implementation**

Add a `LinkedHashMap<String, McpToolHandleBinding>` field with synchronized accessors. Do not persist the raw Handle in MySQL; it is a session runtime capability.

- [ ] **Step 4: Run test to verify it passes**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContext.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolContextSchemaTest.java
git commit -m "feat: 增加MCP工具会话句柄"
```

### Task 2: MCP 工具发现与 Top 3 Schema 返回

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolDiscoveryTool.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolDiscoveryToolTest.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActToolAllowlistPolicy.java`

**Interfaces:**
- Add allowlist ID `discover_mcp_tools`.
- Add `@Tool(name = "discover_mcp_tools") String discoverMcpTools(String query, String mcpId, Integer limit)`.
- Return JSON `{query, limit, candidates, message}`; each candidate includes `toolHandle`, `mcpId`, `toolName`, `description`, `inputSchema`, `schemaHash`.

- [ ] **Step 1: Write the failing tests**

Cover three behaviors:

```java
@Test
void returnsAtMostThreeMatchingCandidatesWithFullSchemas() { ... }

@Test
void neverSearchesAnUnboundMcp() { ... }

@Test
void returnsNotFoundInsteadOfGuessingWhenNoToolMatches() { ... }
```

The first test stubs four tools across a bound MCP and asserts three candidates, `get_today_feedback` ranked first for `查询今日库存反馈`, and a nonblank Handle stored in context.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=McpToolDiscoveryToolTest' test`

Expected: test compilation fails because `McpToolDiscoveryTool` and the allowlist ID do not exist.

- [ ] **Step 3: Write minimal implementation**

Implement:

1. Normalize query, MCP ID and limit (`limit` defaults to 3 and is clamped to 1..3).
2. Read `ReActToolContext.getBoundMcpIds()`; reject an explicit unbound MCP.
3. Resolve each client by `AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId)` and call `listTools()`.
4. Score tool name and description using query substring/token matches plus fixed inventory aliases (`库存/缺货/反馈/今日/详情/分诊` and their English forms).
5. Sort deterministically and select at most three.
6. Generate `mcp-tool-` + UUID Handle, compute SHA-256 from the serialized tool schema, store the binding with a ten-minute TTL, and serialize the complete `inputSchema`.
7. Return `MCP_TOOL_NOT_FOUND` or `MCP_TOOL_DISCOVERY_FAILED` without invoking `tools/call` on failure.

- [ ] **Step 4: Run tests to verify they pass**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolDiscoveryTool.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActToolAllowlistPolicy.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolDiscoveryToolTest.java
git commit -m "feat: 增加MCP工具按意图检索"
```

### Task 3: Handle 调用工具与服务端门禁

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolHandleCallTool.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpCallTool.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolHandleCallToolTest.java`

**Interfaces:**
- Add `McpCallTool.callResolvedTool(String mcpId, String toolName, String args, McpSchema.Tool loadedSchema)` for shared execution.
- Add `@Tool(name = "call_mcp_tool") String callMcpToolByHandle(String toolHandle, String args)`.
- Keep existing three-argument Java method and its old Schema gate for compatibility; it is no longer selected by the new ReAct runtime.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void rejectsHandleFromAnotherSessionBeforeCallingMcp() { ... }

@Test
void rejectsExpiredHandleBeforeCallingMcp() { ... }

@Test
void resolvesHandleAndCallsExactMcpTool() { ... }
```

Assert that rejected cases never invoke `McpSyncClient.callTool`, while the valid case sends the exact tool name and arguments stored by discovery.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=McpToolHandleCallToolTest' test`

Expected: FAIL because Handle lookup and the new tool do not exist.

- [ ] **Step 3: Write minimal implementation**

Resolve the Handle from `ReActToolContext`, check session/Agent, TTL and schema hash, then delegate to the shared `McpCallTool` executor. The executor must validate the live tool name and required arguments before constructing `McpSchema.CallToolRequest`.

- [ ] **Step 4: Run tests to verify they pass**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolHandleCallTool.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpCallTool.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/tools/mcp/McpToolHandleCallToolTest.java
git commit -m "feat: 增加MCP句柄调用门禁"
```

### Task 4: Runtime 装配与 ReAct 提示词迁移

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/runtime/AgentRuntimeBindingService.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/agent/AgentRuntimeBindingServiceTest.java`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategyTest.java` (create if absent)

**Interfaces:**
- Auto-add `discover_mcp_tools` and `call_mcp_tool` when MCP is bound.
- ReAct strategy injects `McpToolDiscoveryTool` and `McpToolHandleCallTool` for new runtime calls.
- Remove the prompt block that enumerates every MCP tool and required argument; keep only MCP ID/name/description and discovery instructions.
- Keep explicit legacy `get_mcp_tool_schema` support when an old Agent explicitly binds it.

- [ ] **Step 1: Write failing tests**

Assert effective tools contain discovery + call, do not auto-add `get_mcp_tool_schema`, and the generated prompt contains `discover_mcp_tools` but not a complete MCP tool catalog.

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=AgentRuntimeBindingServiceTest,ReActExecuteStrategyTest' test`

Expected: FAIL against the current auto-bound Schema tool and prompt catalog.

- [ ] **Step 3: Implement runtime migration**

Update allowlist options, runtime binding, bean injection and prompt rules. Explicitly state: “工具发现无结果时禁止猜测工具名；发现结果返回候选完整 Schema 后直接使用 toolHandle 调用”。

- [ ] **Step 4: Run focused tests**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/runtime/AgentRuntimeBindingService.java ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/agent/AgentRuntimeBindingServiceTest.java ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategyTest.java
git commit -m "feat: 将ReAct迁移到MCP工具发现"
```

### Task 5: 全链路回归与推送前验证

**Files:**
- Test: existing MCP, Agent binding, Feedback ingestion and ReAct test suites
- Modify: none unless a regression is found

- [ ] **Step 1: Run focused regression**

```bash
mvn -pl ai-agent-station-study-trigger -am '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=McpToolDiscoveryToolTest,McpToolHandleCallToolTest,McpCallToolSchemaGateTest,AgentRuntimeBindingServiceTest,AgentControllerTest,ChatAgentRoutePolicyTest,McpFeedbackIngestionServiceTest' test
```

- [ ] **Step 2: Run the full suite**

```bash
mvn '-DskipTests=false' test
```

Expected: BUILD SUCCESS, no test failures or errors.

- [ ] **Step 3: Check the diff**

```bash
git diff --check
git status --short
```

Only feature files may be staged; preserve the existing untracked resume, interview documents and temporary files.

- [ ] **Step 4: Commit any regression-only changes**

```bash
git commit -m "test: 完成MCP工具发现链路回归验证"
```
