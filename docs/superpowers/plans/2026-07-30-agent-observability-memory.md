# Agent 观测与长期记忆实施计划

> 面向协作开发 Agent：本计划已按 `executing-plans` 流程执行。所有状态用复选框追踪。

**目标：** 构建企业级 Agent 对话运行链路与长期记忆召回能力，让“思考 / 路由 / 工具调用 / 子 Agent / Todo / Feedback / Case”能被清晰追溯。

**架构：** 后端新增 `ConversationTraceService` 聚合消息、LLM 调用、工具结果、执行状态、Feedback、Case、Todo、子 Agent 任务，输出稳定 trace 视图；前端日志页渲染企业级时间线；长期记忆提供按 `agentId` 隔离的召回接口，并在仪表盘提供可测试入口。

**技术栈：** Spring Boot 3、MyBatis、MySQL、PostgreSQL/pgvector 兼容记忆表、Vue 3、Vite、JUnit 5。

## 全局约束

- 不覆盖用户本地脏改：`ai-agent-station-study-app/src/main/java/cn/bugstack/ai/Application.java`。
- 行为修改保持测试覆盖。
- 自动采集必须精确，避免把“1 / 你好”等噪声写入业务仪表盘。
- Trace 与记忆数据必须按 `agentId + sessionId` 隔离。
- 中文网站的用户可见文案保持中文。
- 每个可独立交付切片用清晰中文 Conventional Commit 提交并推送。

---

### Task 1：后端会话 Trace 视图

**文件：**

- 新增：`ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/observability/ConversationTraceService.java`
- 新增/修改测试：`ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/observability/ConversationTraceServiceTest.java`
- 修改：`ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/DashboardController.java`

**接口：**

- 输入：`agentId`、`sessionId`、`IChatMessageDao`、`IAiLlmLogDao`、Feedback/Case DAO 等。
- 输出：`ConversationTraceService.trace(agentId, sessionId)`，包含 `agentId`、`sessionId`、`summary`、`timeline`。

- [x] 写测试：trace 按时间排序用户、助手、工具、LLM 事件。
- [x] 写测试：同 session 下拒绝混入其他 Agent 的消息。
- [x] 实现 `ConversationTraceService`。
- [x] 增加 `GET /api/v1/agents/{agentId}/sessions/{sessionId}/trace`。
- [x] 运行目标测试。
- [x] 提交：`feat: 结构化展示Agent执行轨迹`。

### Task 2：结构化工具与执行时间线

**文件：**

- 修改：`ConversationTraceService.java`
- 修改测试：`ConversationTraceServiceTest.java`
- 读取并接入 DAO：`IMemoryToolResultDao`、`IAgentExecutionDao`、`ISubagentTaskDao`。

**接口：**

- 输入：工具消息、memory tool result、子 Agent 任务、执行记录。
- 输出：带 `type`、`title`、`status`、`toolName`、`toolSource`、`input`、`outputPreview`、`durationMs`、`errorMessage` 的时间线事件。

- [x] 写测试：工具事件暴露结构化输入、输出、状态。
- [x] 写测试：失败工具事件暴露中文失败摘要。
- [x] 实现工具事件映射。
- [x] DAO 数据存在时纳入 execution / subagent / todo 事件。
- [x] 运行目标测试与关键回归测试。
- [x] 提交：`feat: 结构化展示Agent执行轨迹`。

### Task 3：前端运行链路工作台

**文件：**

- 修改：`frontend-vue/src/App.vue`
- 修改：`frontend-vue/src/styles.css`

**接口：**

- 输入：`/api/v1/agents/{agentId}/sessions/{sessionId}/trace`
- 输出：中文时间线 UI，支持长内容折叠、失败高亮、模型摘要、原始消息查看。

- [x] 选中日志会话时加载 trace。
- [x] 渲染路由、模型、工具、Todo、子 Agent、消息卡片。
- [x] 增加长内容展示与滚动样式。
- [x] 对存在来源 session/case 的事件提供跳转入口。
- [x] 运行 `npm run build`。
- [x] 提交：`feat: 升级Agent运行追踪前端视图`。

### Task 4：长期记忆召回

**文件：**

- 新增：`ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/LongTermMemoryRecallService.java`
- 修改：`AgentOperationsController.java`
- 新增测试：`ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/LongTermMemoryRecallServiceTest.java`

**接口：**

- 输入：`agentId`、用户查询、记忆摘要、Agent Profile、pgvector 长期记忆。
- 输出：召回结果，包含 `sourceType`、`sourceId`、`summary`、`score`、`sourceSessionId`、`sourceCaseId`、`createdAt`。

- [x] 写测试：只返回请求 Agent 的记忆。
- [x] 写测试：未解决/候选 Case 记忆不进入召回结果。
- [x] 实现 pgvector 兼容召回，并 fallback 到 profile/summary。
- [x] 暴露 `/api/v1/agents/{agentId}/memory/recall?query=...`。
- [x] 运行目标测试与关键回归测试。
- [x] 提交：`feat: 增加Agent长期记忆召回接口`。

### Task 5：仪表盘记忆面板与最终验证

**文件：**

- 修改：`frontend-vue/src/App.vue`
- 修改：`frontend-vue/src/styles.css`
- 修改：本计划 TODO 状态。

**接口：**

- 输入：长期记忆召回接口与现有 Agent Profile 接口。
- 输出：仪表盘展示可召回的长期记忆摘要，并支持跳转到来源链路。

- [x] 增加仪表盘长期记忆召回面板。
- [x] 更新实施 TODO 状态。
- [x] 运行后端关键回归测试套件。
- [x] 运行前端 `npm run build`。
- [x] 提交：`feat: 增加长期记忆召回前端面板`。
- [x] 提交并推送本文档状态更新。
