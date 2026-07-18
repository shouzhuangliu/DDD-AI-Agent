# Agent Conversation Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each Agent own persistent UUID conversations, versioned Soul Markdown, released capabilities, and traceable Case/Feedback sources.

**Architecture:** Keep existing MyBatis and controller conventions. Add a small session lifecycle service that owns UUID creation and ownership validation, plus a Soul version service that writes Markdown revisions and mirrors the active revision to the existing runtime system prompt. The single-page UI consumes these APIs to list, reopen and continue conversations.

**Tech Stack:** Java 21, Spring Boot, MyBatis, MySQL 8, vanilla JavaScript, Maven/JUnit.

## Global Constraints

- No tenants, users, authentication, organizations, or Git commits.
- New sessions are server-generated canonical UUIDs; legacy `sess-*` sessions remain readable.
- API keys must never be returned or written to logs.
- Bind only released Skill and MCP runtime capabilities.

---

### Task 1: Session lifecycle schema and domain contract

**Files:**
- Modify: `scripts/migrations/V20260717__agent_conversation_continuity.sql`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AiSession.java`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IAiSessionDao.java`
- Modify: `ai-agent-station-study-app/src/main/resources/mybatis/mapper/ai_session_mapper.xml`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/conversation/ConversationIdPolicyTest.java`

**Interfaces:**
- Produces `ConversationIdPolicy.create()` and `ConversationIdPolicy.isCanonicalUuid(String)`.
- Produces DAO methods `touch(sessionId, preview, modelId)` and `queryByAgentAndSession(agentId, sessionId)`.

- [ ] Write failing tests asserting generated IDs match `UUID.fromString(id)` and timestamp-style IDs are not canonical UUIDs.
- [ ] Run `mvn -pl ai-agent-station-study-trigger -Dtest=ConversationIdPolicyTest test`; expect compilation failure because policy does not exist.
- [ ] Create `ConversationIdPolicy` using `UUID.randomUUID().toString()` and a strict parse/equality validator.
- [ ] Add idempotent migration columns `model_id`, `last_message_at`, `preview` to `ai_session`, then map them in PO and MyBatis SQL. `touch` updates `message_count`, `updated_at`, `last_message_at`, `preview`, and `model_id`; ordering uses `COALESCE(last_message_at, updated_at)` descending.
- [ ] Run the focused test; expect PASS.

### Task 2: UUID session APIs and message lifecycle integration

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/conversation/ConversationSessionService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentController.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ChatMessageRecorderDb.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/conversation/ConversationSessionServiceTest.java`

**Interfaces:**
- Consumes Task 1 policy and session DAO.
- Produces `create(agentId, title, modelId)`, `requireOwned(agentId, sessionId)`, and `detail(agentId, sessionId)`.
- `POST /api/v1/agents/{agentId}/sessions` creates sessions; `GET /api/v1/agents/{agentId}/sessions` lists; `GET /api/v1/agents/{agentId}/sessions/{sessionId}` returns session/messages/memory.

- [ ] Write failing mocked DAO tests for UUID creation, cross-Agent rejection and details containing messages plus memory.
- [ ] Run the focused test; expect FAIL because service does not exist.
- [ ] Implement service validation against Agent DAO and session DAO, and adapt controller endpoints. Keep legacy `/sessions` endpoints as delegating compatibility endpoints.
- [ ] Inject session DAO into `ChatMessageRecorderDb`; after each user/assistant/tool record, call `touch` using a content preview and never include tool arguments in preview.
- [ ] Ensure chat execution controller calls `requireOwned` for supplied UUID sessions before dispatching Auto/ReAct.
- [ ] Run focused tests; expect PASS.

### Task 3: Versioned Soul assets and model validation

**Files:**
- Modify: `scripts/migrations/V20260717__agent_conversation_continuity.sql`
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/agent/AgentSoulService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentController.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/agent/AgentSoulServiceTest.java`

**Interfaces:**
- Produces `saveVersion(agentId, markdown, actor)` and `activate(agentId, version, actor)`.
- Exposes `GET/POST /api/v1/agents/{agentId}/souls` and `POST /api/v1/agents/{agentId}/souls/{version}/activate`.

- [ ] Write failing JDBC-backed service tests for monotonically increasing version numbers and single ACTIVE revision after activate.
- [ ] Run focused test; expect FAIL because table/service does not exist.
- [ ] Add `agent_soul_version` with unique `(agent_id, version)`, active lookup index and status/audit fields. Implement SQL through `JdbcTemplate` transactionally: insert revisions as DRAFT, deactivate current revision, activate selected revision, and update `ai_agent.system_prompt` with Markdown.
- [ ] Change Agent create/update validation to require the selected default model exists and is configured, returning a safe validation error otherwise.
- [ ] Run focused Soul and model tests; expect PASS.

### Task 4: Source navigation DTOs for Case and Feedback

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/http/ConversationTraceContractTest.java`

**Interfaces:**
- Produces a `source` object with `agentId`, `sessionId`, `messageId`, `role`, and safe content preview in Case evidence and Feedback responses.

- [ ] Write failing MVC/JDBC contract tests that source mapping rejects cross-Agent messages and includes the exact conversation identifiers for valid evidence/feedback.
- [ ] Run focused test; expect FAIL for missing `source` field.
- [ ] Add a controller-local source mapper querying `chat_message` by message ID and validating agent/session ownership. Enrich Case detail evidence and Feedback list entries without altering existing fields.
- [ ] Run focused tests; expect PASS.

### Task 5: Agent editor and persistent conversation UI

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/resources/static/index.html`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/http/StaticUiContractTest.java`

**Interfaces:**
- Uses Task 2 session endpoints and Task 3 Soul endpoints.
- Uses `openConversation(agentId, sessionId, messageId)` as the single navigation path for history and source links.

- [ ] Write a failing static contract test asserting the page calls Agent-scoped session creation, loads session detail before rendering, and does not contain `sess-` generation.
- [ ] Run focused test; expect FAIL while the old browser timestamp code remains.
- [ ] Replace new-chat flow with `POST /agents/{agentId}/sessions`; render session cards with title, UUID prefix, last activity, count and preview; on open call detail API and render persisted user/assistant messages plus a collapsed memory notice.
- [ ] Populate Agent creation/edit model selectors from `/models`, keep unconfigured options disabled, and add Soul history/save/activate modal actions. Show released binding summaries using existing capability APIs.
- [ ] Add source buttons to Case/Feedback rows which invoke `openConversation` and scroll/highlight the target message.
- [ ] Run static contract test; expect PASS.

### Task 6: Migration, integration verification and browser acceptance

**Files:**
- Modify: `docs/superpowers/specs/2026-07-17-agent-conversation-continuity-design.md` only if verification exposes an explicit design correction.

- [ ] Run migration against the local MySQL container and query the new table/index/columns.
- [ ] Run `mvn -pl ai-agent-station-study-trigger -am test` and `mvn -DskipTests package`; expect both PASS.
- [ ] Restart the application with existing environment-based model credentials without printing them.
- [ ] Smoke test: create a session, assert it is UUID, send one message, reload session detail, then submit feedback against the stored assistant message.
- [ ] Open the browser at `http://localhost:8091`, visually verify Agent history, model selector, Soul dialog and source navigation.
