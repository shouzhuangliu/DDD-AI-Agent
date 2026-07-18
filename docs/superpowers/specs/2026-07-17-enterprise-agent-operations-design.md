# Enterprise Agent Operations Design

## Objective

Evolve the prototype into an agent-scoped operations platform with trustworthy feedback semantics, explainable case extraction and ranking, durable short-term memory, and governed MCP/Skill supply chains.

The system remains single-tenant for this release. Local development must continue to run from IDEA with MySQL and PostgreSQL/pgvector supplied by `compose.local.yml`.

## Delivery order

1. Agent-scoped Case and dual-channel Feedback.
2. Asynchronous conversation analysis and explainable ranking.
3. Four-layer short-term memory; long-term memory is interface-only.
4. Enterprise MCP registration, testing, review, release, monitoring, and rollback.
5. Enterprise Skill ZIP quarantine, validation, testing, review, signing, release, and rollback.

## Agent workspace

The main dashboard is an Agent workspace. The selected `agentId` is carried in the URL and applied to every statistic and detail query. A separate global comparison page may aggregate agents, but global and per-agent details must not be mixed.

The workspace contains metrics, important Cases, satisfaction and Feedback trends, and tabs for Cases, Feedback, conversation analysis, memory, MCP/Skills, and logs. Case and Feedback details link back to the exact assistant response and source evidence.

## Feedback semantics

Feedback has two explicitly separated channels:

- Explicit feedback is a deliberate user action: thumbs up/down, rating, written comment, correction, or issue report. It targets an assistant message.
- AI signals are inferred from conversation evidence: user correction, repeated question, irrelevant answer, tool failure, or other quality risk. They are always labelled as AI-derived and never counted as explicit user feedback.

The current behavior that copies every user message into `ai_feedback` is removed. Existing polluted rows are treated as legacy conversation data and excluded from new feedback metrics.

## Case lifecycle and evidence

Cases are scoped to an Agent and follow:

`CANDIDATE -> PENDING_REVIEW -> CONFIRMED -> IN_PROGRESS -> RESOLVED -> ARCHIVED`

Candidates may also become `IGNORED`. High-confidence candidates appear as pending review; low-confidence candidates remain in the candidate pool. Only confirmed and later states enter formal operational statistics.

A Case records title, summary, type, severity, owning Agent, source sessions/messages, model, extraction rationale, confidence, occurrence count, affected sessions, latest occurrence, owner, resolution, and timestamps. Evidence links a Case to messages, explicit Feedback, and AI signals. Case identity is independent of Skill identity.

## Asynchronous analysis

Chat persistence and response delivery remain synchronous. After an assistant response is recorded, an analysis job is created. A database-backed worker claims jobs, calls the configured analysis model, validates structured output, persists signals/candidates/evidence, and records retry/error state. The first release does not require Kafka.

Analysis failures do not fail chat. Jobs are idempotent by source message and analysis policy version, support bounded retries, and can be re-run when the model or policy changes.

## Explainable ranking

Case score is 0-100 with default weights:

- severity: 25%
- explicit negative feedback: 20%
- frequency and affected sessions: 15%
- model-assessed business importance: 15%
- recency: 10%
- unresolved age: 10%
- extraction confidence: 5%

Safety, authorization, and data-loss issues receive a priority floor. Repeated cross-session negative evidence raises priority. Resolved and ignored cases decay. Every score is stored as a snapshot with component values and rationale. Defaults may be overridden per Agent.

## Short-term memory

Short-term memory has four layers:

1. recent full-fidelity turns;
2. persistent rolling summaries for older ranges;
3. structured session state containing goals, constraints, entities, pending work, and completed work;
4. compact tool conclusions with references to full tool messages.

Budgets use model token estimates instead of character counts. Summaries are versioned, record their covered message range and model, and roll forward from the prior summary plus new messages. Failure falls back to recent turns. Agent configuration controls token budget, retained turns, trigger threshold, and summarization model.

Long-term memory defines ports and metadata boundaries only. Automatic cross-session write and retrieval are out of scope until retention, privacy, consent, and sharing rules are decided.

## MCP supply chain

MCP lifecycle:

`DRAFT -> CONNECTIVITY_CHECKED -> DISCOVERED -> SCANNED -> TESTED -> IN_REVIEW -> APPROVED -> RELEASED -> DEPRECATED/WITHDRAWN`

Versions are immutable. Registration supports transport type, endpoint/process configuration, environment, credential references, timeouts, retry and concurrency policies. The platform discovers tools and schemas, performs security scans and sandbox tests, stores reports, enforces review separation, publishes environment releases, and supports canary binding, monitoring, circuit breaking, rollback, and retirement. Agents bind released versions, never mutable drafts.

## Skill supply chain

Skill lifecycle:

`UPLOADED -> QUARANTINED -> VALIDATED -> SCANNED -> TESTED -> IN_REVIEW -> APPROVED -> SIGNED -> RELEASED -> DEPRECATED/WITHDRAWN`

ZIP ingestion prevents path traversal, archive bombs, forbidden links/executables, and file/size limit violations. Validation checks the manifest, `SKILL.md`, entry points, versions, and dependencies. Security analysis detects suspicious prompt/tool behavior. Sandbox loading and tests produce immutable reports. Approved artifacts are hashed, signed, versioned, released, monitored, and reversible. Agents bind released versions only.

## Roles and audit

Built-in roles are Developer, Tester, Security Reviewer, Release Manager, and Agent Administrator. Submitters cannot independently security-approve and production-release the same version. All state transitions, reviews, bindings, test executions, releases, rollbacks, and configuration changes write immutable audit records with actor, reason, before/after state, and time.

## Core persistence boundaries

- Analysis: `analysis_job`, `ai_signal`, `ai_feedback`, `ai_case`, `case_evidence`, `case_score_snapshot`, `case_review_record`.
- Memory: `memory_summary`, `memory_state`, `memory_tool_result`.
- MCP: `mcp_server`, `mcp_version`, `mcp_discovered_tool`, `mcp_test_case`, `mcp_test_run`, `mcp_security_scan`, `mcp_review`, `mcp_release`, `agent_mcp_binding`.
- Skill: `skill_package`, `skill_version`, `skill_artifact`, `skill_dependency`, `skill_validation`, `skill_test_run`, `skill_review`, `skill_release`, `agent_skill_binding`.
- Shared governance: `audit_log`.

Business workflow data remains in MySQL. PostgreSQL/pgvector remains available for future semantic retrieval and duplicate-case clustering; long-term memory behavior is not activated in this release.

## API rules

- Agent-scoped endpoints include `agentId` in the path.
- Mutating workflow endpoints require an expected current state and reject invalid transitions.
- Secrets are accepted only through credential-management operations and are never returned by read APIs.
- Long-running validation, analysis, test, and release operations return job/run identifiers.
- List endpoints support paging, filtering, stable sorting, and server-side authorization.
- All DTOs expose source type and lifecycle state explicitly.

## Migration and compatibility

- Add Agent ownership and new lifecycle fields without deleting original chat records.
- Exclude legacy auto-created feedback from explicit-feedback metrics.
- Convert old MCP/Skill checkbox bindings into unpublished drafts; do not silently approve them.
- Preserve current chat and model-switching APIs while adding analysis jobs after message persistence.
- Schema changes are idempotent for local reset and repeatable startup.

## Acceptance criteria

- Selecting an Agent changes all workspace data and details.
- A normal user message never creates explicit Feedback.
- Explicit Feedback targets an assistant response; AI signals are visually and statistically distinct.
- Case ranking is explainable and evidence-backed.
- Chat succeeds when analysis or summarization fails.
- Short-term summaries persist and are traceable to source messages.
- MCP cannot be bound before tested, reviewed, and released.
- Skill ZIP cannot leave quarantine before safety and structure validation passes.
- Submitter/reviewer/releaser separation is enforced and audited.
- The complete application builds and runs locally with the existing Docker dependencies.
