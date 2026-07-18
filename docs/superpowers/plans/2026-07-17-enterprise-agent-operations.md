# Enterprise Agent Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an Agent-scoped operations workspace, trustworthy Feedback/Case analysis, durable short-term memory, and governed MCP/Skill release workflows.

**Architecture:** Keep chat synchronous and persist database-backed asynchronous analysis jobs. Put workflow rules in focused domain services, MyBatis persistence in infrastructure, REST orchestration in trigger, and render a single Agent-scoped workspace from the existing static frontend. MySQL owns workflow state; pgvector remains reserved for later semantic retrieval.

**Tech Stack:** Java 17, Spring Boot 3.4.3, Spring AI 1.0.0, MyBatis, MySQL 8, PostgreSQL/pgvector, vanilla HTML/JavaScript.

## Global Constraints

- Single tenant for this release; do not add `tenant_id`.
- Preserve the current chat and per-request model switching behavior.
- Never return provider keys or MCP credentials from read APIs.
- No Git commit, push, reset, checkout, or destructive cleanup.
- Existing dirty-worktree changes belong to the user and must be preserved.
- New behavior follows RED-GREEN-REFACTOR and is verified with `mvn -DskipTests=false`.

---

### Task 1: Feedback and Case domain contracts

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/operations/CaseScoringService.java`
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/operations/WorkflowTransitionPolicy.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/CaseScoringServiceTest.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/WorkflowTransitionPolicyTest.java`

**Interfaces:**
- Produces: `CaseScoringService.score(CaseScoreInput): CaseScoreBreakdown` and `WorkflowTransitionPolicy.requireAllowed(resource, from, to)`.

- [ ] Write tests proving weighted score components, priority floors, score bounds, and valid/invalid lifecycle transitions.
- [ ] Run the focused tests and observe missing-class failures.
- [ ] Implement immutable input/output records and explicit transition maps.
- [ ] Re-run the focused tests and the domain test suite.

### Task 2: MySQL operational schema and persistence

**Files:**
- Modify: `create_tables.sql`
- Create: `scripts/migrations/V20260717__enterprise_agent_operations.sql`
- Modify/Create PO, DAO, and mapper files under `ai-agent-station-study-infrastructure/...` and `ai-agent-station-study-app/src/main/resources/mybatis/mapper/` for `analysis_job`, `ai_signal`, enhanced `ai_feedback`, enhanced `ai_case`, `case_evidence`, `case_score_snapshot`, and `case_review_record`.

**Interfaces:**
- Produces: Agent-scoped paged queries, idempotent analysis-job insertion/claiming, explicit feedback insertion, evidence persistence, and score snapshots.

- [ ] Add a schema contract test that reads the migration and asserts required tables, Agent ownership, message linkage, source type, state, score, indexes, and uniqueness keys.
- [ ] Run it and observe missing schema clauses.
- [ ] Add idempotent local DDL and a non-destructive migration; mark legacy Feedback as `LEGACY_AUTO_CAPTURE`.
- [ ] Add PO/DAO/mappers with every list/count query requiring `agentId`.
- [ ] Run mapper parsing/build verification.

### Task 3: Explicit Feedback API and Agent workspace queries

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java`
- Create: request DTOs under `ai-agent-station-study-api/src/main/java/cn/bugstack/ai/api/dto/operations/`.
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/DashboardController.java`
- Modify: `ai-agent-station-study-trigger/src/main/resources/static/index.html`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/FeedbackRequestTest.java`

**Interfaces:**
- Produces: `POST /api/v1/agents/{agentId}/feedback`, `GET /api/v1/agents/{agentId}/workspace/stats`, `GET /api/v1/agents/{agentId}/feedback`, `GET /api/v1/agents/{agentId}/signals`, and `GET /api/v1/agents/{agentId}/cases`.

- [ ] Test validation requiring session, target assistant message, and explicit feedback type.
- [ ] Observe validation test failure.
- [ ] Implement DTO validation and Agent/message consistency checks.
- [ ] Remove the frontend automatic Feedback POST from chat send.
- [ ] Add thumbs-up/down and written feedback actions to assistant messages.
- [ ] Add Agent selector persistence in the URL and route all workspace requests through it.
- [ ] Verify normal chat no longer invokes the Feedback endpoint.

### Task 4: Asynchronous conversation analysis

**Files:**
- Create services under `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/`.
- Modify: `ChatMessageRecorderDb.java` to enqueue only after an assistant message is persisted.
- Modify: `application-dev.yml` with bounded worker configuration.
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/AnalysisResultParserTest.java`

**Interfaces:**
- Produces: idempotent `enqueue(agentId, sessionId, assistantMessageId)`, job claim/retry, strict JSON parsing, signal/candidate/evidence persistence, and score snapshots.

- [ ] Test strict parsing, malformed-output rejection, idempotency key construction, and bounded retry decisions.
- [ ] Observe failures before implementation.
- [ ] Implement a structured analysis prompt and parser with no markdown-fence dependency.
- [ ] Implement scheduled database worker with lease/attempt fields and failure isolation.
- [ ] Persist AI signals separately from explicit Feedback and score extracted Cases.
- [ ] Verify chat recorder still succeeds when enqueue or analysis fails.

### Task 5: Four-layer short-term memory

**Files:**
- Create memory PO/DAO/mappers for `memory_summary`, `memory_state`, and `memory_tool_result`.
- Create: `TokenBudgetEstimator.java`, `RollingSummaryPolicy.java`, and persistence adapter under the existing memory package.
- Modify: `MemoryFoldingPipeline.java` and `ChatMessageRecorderDb.java`.
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/memory/RollingSummaryPolicyTest.java`.

**Interfaces:**
- Produces: recent-turn window, versioned persistent summary, structured state, compact tool conclusion, and `LongTermMemoryPort` with no active implementation.

- [ ] Test token-budget triggering, covered message ranges, summary roll-forward, and fallback behavior.
- [ ] Observe policy tests fail.
- [ ] Implement token estimator and rolling policy.
- [ ] Persist summaries and structured state without modifying original chat messages.
- [ ] Assemble prompt context from summary/state/tool conclusions/recent turns.
- [ ] Add Agent memory inspection endpoints and workspace panel.
- [ ] Verify summary failure falls back to recent turns.

### Task 6: Enterprise MCP registry

**Files:**
- Create MCP registry PO/DAO/mappers for server, immutable version, discovered tool, test case/run, scan, review, release, binding, and audit.
- Create lifecycle/security/test services under `domain/.../capability/mcp/` and trigger orchestration under `trigger/service/capability/mcp/`.
- Create: `McpRegistryController.java`.
- Modify the MCP frontend page into registration, testing, review, release, binding, and monitoring views.
- Test lifecycle and secret-redaction behavior.

**Interfaces:**
- Produces: draft registration, connectivity check, discovery, scan, sandbox test, separated reviews, immutable release, rollback, and released-version Agent binding.

- [ ] Test lifecycle guards, submitter separation, released-version immutability, and DTO secret redaction.
- [ ] Observe failures.
- [ ] Implement schema/persistence and lifecycle policy.
- [ ] Reuse the existing MCP client registration for connectivity/discovery behind a test runner.
- [ ] Add review/release/rollback endpoints and audit each transition.
- [ ] Reject binding of any version without an active release.
- [ ] Verify MCP secrets never appear in list/detail JSON.

### Task 7: Enterprise Skill ZIP supply chain

**Files:**
- Create Skill registry PO/DAO/mappers for package, version, artifact, dependency, validation, test, review, release, binding, and audit.
- Create: `SafeSkillArchiveValidator.java`, lifecycle services, and `SkillRegistryController.java`.
- Modify the Skills frontend page into upload, validation, review, release, and binding views.
- Test: archive traversal, archive limits, manifest/SKILL.md requirements, review separation, signing hash, and binding guards.

**Interfaces:**
- Produces: multipart ZIP upload to quarantine, deterministic validation report, immutable SHA-256 artifact identity, review, release, rollback, and released-version Agent binding.

- [ ] Write ZIP safety and lifecycle tests first and observe failures.
- [ ] Implement bounded streaming extraction that rejects absolute/traversal paths, symlinks, forbidden executables, too many files, oversized entries, and oversized total output.
- [ ] Validate manifest, SKILL.md, version, and declared dependencies.
- [ ] Persist test/security/review reports and SHA-256 artifact hashes.
- [ ] Enforce distinct submitter, security reviewer, and release manager identities.
- [ ] Publish only immutable approved versions and audit rollback/withdrawal.
- [ ] Verify unsafe archives never leave quarantine.

### Task 8: Integrated verification and local runtime

**Files:**
- Modify: `docs/local-development.md`
- Modify: `README.md`

**Interfaces:**
- Consumes all earlier endpoints and persistence contracts.

- [ ] Apply the migration to the local MySQL container and verify required tables/indexes.
- [ ] Run `mvn -DskipTests=false test` and record zero failures.
- [ ] Run `mvn -DskipTests=true package` and record exit code 0.
- [ ] Restart the application from the built artifact without stopping Docker data services.
- [ ] Smoke-test Agent workspace, explicit Feedback, Case lists, memory inspection, MCP lifecycle guards, Skill upload guards, models, and chat.
- [ ] Confirm `http://localhost:8091/` returns HTTP 200 and leave the application running.
