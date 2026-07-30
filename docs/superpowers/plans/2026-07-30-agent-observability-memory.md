# Agent Observability and Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an enterprise-grade Agent conversation timeline and long-term memory recall surface.

**Architecture:** Add a backend `ConversationTraceService` that assembles existing logs, messages, tool results, execution state, feedback, and cases into one stable trace view. Then improve the Vue log page to render timeline cards and add a memory recall endpoint constrained by `agentId`.

**Tech Stack:** Spring Boot 3, MyBatis, MySQL, PostgreSQL/pgvector-compatible memory tables, Vue 3, Vite, JUnit 5.

## Global Constraints

- Do not overwrite the user's local dirty `ai-agent-station-study-app/src/main/java/cn/bugstack/ai/Application.java`.
- Use TDD for behavior changes.
- Keep automatic data capture precise; no noise should enter business dashboards.
- All trace and memory data must be scoped by `agentId + sessionId` where applicable.
- User-facing website copy must be Chinese.
- Commit and push each coherent slice with a clear Chinese conventional commit message.

---

### Task 1: Backend Conversation Trace View

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/observability/ConversationTraceService.java`
- Create/Modify tests: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/observability/ConversationTraceServiceTest.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/DashboardController.java`

**Interfaces:**
- Consumes: `agentId`, `sessionId`, `IChatMessageDao`, `IAiLlmLogDao`, feedback/case DAOs if available.
- Produces: `ConversationTraceService.trace(agentId, sessionId)` returning a map with `agentId`, `sessionId`, `summary`, `timeline`.

- [ ] Write failing test: trace sorts user, assistant, tool, and LLM events by time.
- [ ] Write failing test: trace rejects messages from another agent in same session.
- [ ] Implement `ConversationTraceService`.
- [ ] Add `GET /api/v1/agents/{agentId}/sessions/{sessionId}/trace`.
- [ ] Run targeted tests.
- [ ] Commit: `feat: 增加Agent会话运行轨迹接口`.

### Task 2: Structured Tool and Execution Timeline

**Files:**
- Modify: `ConversationTraceService.java`
- Modify tests: `ConversationTraceServiceTest.java`
- Inspect existing DAOs: `IMemoryToolResultDao`, `IAgentExecutionDao`, `ISubagentTaskDao`.

**Interfaces:**
- Consumes: tool messages, memory tool results, subagent tasks, execution records.
- Produces: timeline events with `type`, `title`, `status`, `toolName`, `toolSource`, `input`, `outputPreview`, `durationMs`, `errorMessage`.

- [ ] Write failing test: tool events expose structured input/output/status.
- [ ] Write failing test: failed tool events expose Chinese failure summary.
- [ ] Implement tool event mapping.
- [ ] Include execution/subagent/todo events when DAO data exists.
- [ ] Run targeted tests and key regression tests.
- [ ] Commit: `feat: 结构化展示工具与执行轨迹`.

### Task 3: Frontend Timeline Workbench

**Files:**
- Modify: `frontend-vue/src/App.vue`
- Modify: `frontend-vue/src/styles.css`

**Interfaces:**
- Consumes: `/api/v1/agents/{agentId}/sessions/{sessionId}/trace`
- Produces: Chinese timeline UI with collapsible long content and highlighted failures.

- [ ] Add trace loader when selecting log session.
- [ ] Render route/model/tool/todo/subagent/message cards.
- [ ] Add long content collapse styles.
- [ ] Add Feedback/Case jump buttons when references exist.
- [ ] Run `npm run build`.
- [ ] Commit: `feat: 打磨Agent运行轨迹时间线界面`.

### Task 4: Long-Term Memory Recall

**Files:**
- Modify/Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/*`
- Modify: `AgentOperationsController.java` or a dedicated memory controller
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/*`

**Interfaces:**
- Consumes: `agentId`, user query, memory summaries, agent profile, pgvector memory if available.
- Produces: recall result with `sourceType`, `sourceId`, `summary`, `score`, `createdAt`.

- [ ] Write failing test: recall only returns memory for the requested agent.
- [ ] Write failing test: unresolved candidate Case memory is not returned.
- [ ] Implement recall service with pgvector-compatible fallback to profile/summary.
- [ ] Expose `/api/v1/agents/{agentId}/memory/recall?query=...`.
- [ ] Run targeted tests and key regression tests.
- [ ] Commit: `feat: 增加Agent长期记忆召回接口`.

### Task 5: Dashboard Memory and Final Verification

**Files:**
- Modify: `frontend-vue/src/App.vue`
- Modify: `frontend-vue/src/styles.css`
- Modify docs TODO checkboxes.

**Interfaces:**
- Consumes: memory recall endpoint and existing profile endpoint.
- Produces: dashboard section showing callable long-term memory summaries.

- [ ] Add dashboard memory recall panel.
- [ ] Update implementation TODO checkboxes to match completed work.
- [ ] Run backend key regression test suite.
- [ ] Run frontend `npm run build`.
- [ ] Commit: `feat: 完成Agent观测与长期记忆阶段打磨`.
- [ ] Push to `origin/main`.
