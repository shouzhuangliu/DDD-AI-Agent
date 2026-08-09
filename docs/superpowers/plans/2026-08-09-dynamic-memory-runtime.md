# 动态记忆运行时实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让简历中描述的“按模型窗口比例折叠、ReAct 工具调用可恢复、长短期记忆不阻塞主流程”在代码中形成可验证闭环。

**Architecture:** 在 domain 增加独立的模型上下文预算策略，用模型窗口、输出预留、系统提示词和工具描述计算本次推理的软/硬阈值；Chat 和 ReAct 在每次模型调用前使用同一策略折叠上下文。ReAct 以消息 Map 作为回合级规范表示，每轮调用前重新折叠，并把 assistant tool_calls 与 tool 结果成对持久化；折叠结果通过带分页的内部取回工具恢复。摘要服务拆为“快照读取/模型调用/短事务提交”，避免模型调用占用数据库事务。

**Tech Stack:** Spring Boot、Spring AI Tool、MyBatis、MySQL、JUnit 4、Maven。

## Global Constraints

- 不改变用户已有的 Chat/ReAct/Auto 路由语义；只修复记忆上下文与工具调用可靠性。
- 模型窗口未知时使用安全默认值，不因缺少配置阻断启动。
- 原始 `chat_message`、工具参数和工具结果永久保留；折叠只作用于发给模型的副本。
- 工具异常、MCP 超时和摘要失败不生成业务 Case 证据。
- 保留现有未跟踪的 `docs/interview/`、`tmp/` 和简历 PDF，不执行清理。
- 每个实现任务遵循 RED → GREEN → REFACTOR，并使用 `mvn -pl ai-agent-station-study-app -am test -DskipTests=false` 验证。

---

### Task 1: 模型窗口比例预算策略

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ModelContextProfile.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ContextBudgetPolicy.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/ContextBudgetPolicyTest.java`
- Modify: `ai-agent-station-study-app/src/main/resources/application-dev.yml`

**Interfaces:**
- `ModelContextProfile.resolve(modelId)` returns a safe profile with `contextWindowTokens`、`maxOutputTokens`、`softSummaryRatio`、`hardFoldRatio`、`safetyMarginTokens`。
- `ContextBudgetPolicy.decide(modelId, systemPrompt, toolDescription, messages)` returns `BudgetDecision`，包含当前估算 token、可用输入 token、软阈值、硬阈值和是否需要折叠。

- [ ] **Step 1: 写失败测试**：验证 32k 模型按 60%/85% 计算阈值，扣除输出预留与安全边界；未知模型使用默认窗口；短消息不触发折叠。
- [ ] **Step 2: 运行测试确认失败**：`mvn -pl ai-agent-station-study-app -am -Dtest=ContextBudgetPolicyTest -DskipTests=false test`，预期因类型不存在或方法未实现失败。
- [ ] **Step 3: 实现最小策略**：增加不可变 profile、配置绑定和 token 估算，阈值由模型窗口比例计算而不是固定 8000/16000。
- [ ] **Step 4: 运行测试确认通过**：重复上述命令，确认所有预算断言通过。
- [ ] **Step 5: 提交**：`git add ... && git commit -m "feat: 增加按模型窗口计算的记忆预算策略"`。

### Task 2: Chat/ReAct 每次推理前动态折叠

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/MemoryFoldingPipeline.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/execute/chat/ChatExecuteStrategy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/MemoryFoldingPipelineTest.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/execute/ReActRoundFoldingTest.java`

**Interfaces:**
- `MemoryFoldingPipeline.fold(messages, ContextBudgetPolicy.BudgetDecision)` 将 token 预算转换为确定性折叠配置。
- ReAct 回合内部使用 `List<Map<String,Object>>` 保存规范会话；每次模型调用前重新执行折叠，再映射为 Spring AI `Message`。

- [ ] **Step 1: 写失败测试**：验证 ReAct 多个工具回合会在第二次模型调用前折叠旧工具结果，且当前轮 user 只出现一次。
- [ ] **Step 2: 运行测试确认失败**：执行定向测试，预期当前实现因只在入口折叠和重复 user 而失败。
- [ ] **Step 3: 实现动态折叠入口**：在 pipeline 增加 BudgetDecision 适配；Chat 使用 system prompt 长度计算；ReAct 每轮由规范 Map 重建 prompt。
- [ ] **Step 4: 修复入口消息顺序**：ReAct 先读取历史，再追加当前 user 并只记录一次，避免把已落库 user 再追加一遍。
- [ ] **Step 5: 运行测试确认通过并重构**：定向测试通过后运行 memory/execute 相关测试。
- [ ] **Step 6: 提交**：`git commit -m "fix: 让模型每轮推理前按窗口比例折叠上下文"`。

### Task 3: assistant tool_calls 与工具结果成对持久化

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ChatMessageRecorder.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/ChatMessageRecorderNoop.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/RetrieveToolCallToolTest.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/ToolCallPersistenceContractTest.java`

**Interfaces:**
- 新增 `recordAssistantToolCalls(sessionId, agentId, turn, step, content, toolCallsJson)`，只写 assistant 原始调用，不重复提交分析任务。
- `findToolExchange` 必须优先返回同会话中、同 tool_call_id 的 assistant/tool 配对；找不到 assistant 时仍安全返回原始工具记录。

- [ ] **Step 1: 写失败测试**：验证 ReAct tool call 写入 assistant `tool_calls_json` 后，工具交换能取到 assistant/tool 两侧数据且不产生重复 analysis job。
- [ ] **Step 2: 运行测试确认失败**：定向测试预期接口缺失或 assistant 为空。
- [ ] **Step 3: 最小实现**：扩展 recorder 接口及 noop/db 实现；ReAct 在执行工具前保存 assistant tool_calls。
- [ ] **Step 4: 运行测试确认通过**：执行持久化契约和既有取回测试。
- [ ] **Step 5: 提交**：`git commit -m "fix: 持久化 ReAct assistant 工具调用配对"`。

### Task 4: 内部取回工具分页与自动装配

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/RetrieveToolCallTool.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/RetrieveToolCallToolTest.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/RetrieveToolCallPaginationTest.java`

**Interfaces:**
- 保留现有 `retrieveToolCall(sessionId, toolCallId)` 兼容入口；增加 `retrieveToolCallPage(sessionId, toolCallId, offset, limit)`，最大单页 20,000 字符，返回 `hasMore/nextOffset/originalChars`。
- ReAct 运行时自动加入低风险内部工具 `retrieve_tool_call`，不要求用户额外勾选；它只能读取当前会话已持久化的调用结果，不执行新命令。

- [ ] **Step 1: 写失败测试**：验证大结果分页、越界 offset、空 ID 和同会话隔离；验证 ReAct 工具列表包含内部取回工具。
- [ ] **Step 2: 运行测试确认失败**：定向测试预期分页字段和自动装配断言失败。
- [ ] **Step 3: 实现分页与自动装配**：复用现有 exchange 查询，按 offset/limit 截取完整原文，严格校验 session/tool ID。
- [ ] **Step 4: 运行测试确认通过**：定向测试及 ReAct allowlist 相关测试通过。
- [ ] **Step 5: 提交**：`git commit -m "feat: 增加会话工具结果分页取回能力"`。

### Task 5: 摘要模型调用与数据库事务解耦

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryPersistenceService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/ShortTermMemoryTransactionTest.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/memory/ShortTermMemorySnapshotTest.java`

**Interfaces:**
- `ShortTermMemoryService.refreshIfNeeded` 只负责读取快照、调用模型和提交结果；模型调用不在 `@Transactional` 范围内。
- `ShortTermMemoryPersistenceService.saveIfUnchanged(snapshot, summary)` 在短事务中校验 cursor/version 后写入摘要、状态和长期记忆候选。

- [ ] **Step 1: 写失败测试**：验证摘要刷新过程中模型调用发生在事务外；并发期间 cursor 已变化时旧摘要不会覆盖新摘要。
- [ ] **Step 2: 运行测试确认失败**：现有 `@Transactional refreshIfNeeded` 结构应无法满足断言。
- [ ] **Step 3: 实现快照/提交分层**：提取快照 DTO，限制工具结果进入摘要 prompt 的长度，使用独立 persistence bean 承担事务写入。
- [ ] **Step 4: 运行测试确认通过**：事务边界与滚动摘要测试通过。
- [ ] **Step 5: 提交**：`git commit -m "fix: 解耦记忆摘要模型调用与数据库事务"`。

### Task 6: 文档、配置和全量验证

**Files:**
- Modify: `docs/memory-folding.md`
- Modify: `ai-agent-station-study-app/src/main/resources/application-dev.yml`
- Create: `docs/superpowers/specs/2026-08-09-dynamic-memory-runtime-design.md`

- [ ] **Step 1: 更新设计文档**：明确模型窗口比例、输出预留、每轮折叠、tool_call 配对和分页取回的真实数据流。
- [ ] **Step 2: 自检计划覆盖**：确认不存在固定 8000/16000 作为唯一触发条件、ReAct 不再重复 user、assistant/tool 可追踪、摘要事务不包 LLM。
- [ ] **Step 3: 运行编译与全量测试**：
  `mvn -pl ai-agent-station-study-app -am clean test -DskipTests=false`
- [ ] **Step 4: 查看版本差异**：`git diff --check` 与 `git status --short`，确保只包含本计划文件和代码，不触碰用户未跟踪文件。
- [ ] **Step 5: 提交**：`git commit -m "docs: 完善动态记忆运行时设计与验证说明"`。

