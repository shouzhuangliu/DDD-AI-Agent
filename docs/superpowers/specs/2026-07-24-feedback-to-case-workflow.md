# Feedback to Case Workflow Design

## Goal

Keep user feedback and business Cases as two separate resources:

```text
user feedback
  -> feedback inbox
  -> AI classification, scoring and duplicate suggestions
  -> human review
      -> reject / request information / keep as feedback
      -> promote to Case
  -> Case owner follows the work
  -> verify resolution
  -> archive and contribute to the Agent long-term profile
```

AI may recommend promotion, but must never bypass human review.

## Existing foundation

Reuse the current `ai_feedback`, `feedback_evaluation_job`, `ai_signal`,
`ai_case`, `case_evidence`, `case_review_record`, `case_score_snapshot` and
`audit_log` tables, plus `WorkflowTransitionPolicy`.

Required cleanup:

1. Move SQL currently in `AgentOperationsController` into DAO classes.
2. Move feedback promotion and Case creation into a transaction service.
3. Add a separate Feedback inbox page and a separate Case workspace page.
4. Record every review and promotion in both the resource timeline and audit log.
5. Do not write a candidate Case into Agent long-term memory.

## Feedback lifecycle

Keep the existing database status values and show friendly labels in the UI:

```text
OPEN -> AI_EVALUATING -> VALID -> CLUSTERED -> PROMOTED -> RESOLVED
  |          |            |          |
  +-> INVALID +-> NEED_MORE_INFO +-> INVALID
```

Human actions:

- Confirm: `OPEN` or `AI_EVALUATING` to `VALID`.
- Request information: `OPEN`, `AI_EVALUATING` or `VALID` to `NEED_MORE_INFO`.
- Reject: `OPEN`, `AI_EVALUATING` or `VALID` to `INVALID`.
- Promote: `VALID` or `CLUSTERED` to `PROMOTED`.

## Case lifecycle

```text
CANDIDATE -> PENDING_REVIEW -> CONFIRMED -> IN_PROGRESS -> RESOLVED -> ARCHIVED
                  |                |             |
                  +-> IGNORED      +-> MERGED     +-> CONFIRMED
```

Promotion should create a `PENDING_REVIEW` Case. `CONFIRMED` is a second
business review gate. A Case must have an owner before `IN_PROGRESS` and a
non-empty resolution before `RESOLVED`.

## Data rules

- Keep the original Feedback row immutable apart from workflow fields.
- Use `case_evidence(evidence_type='FEEDBACK', evidence_id=feedback.id)` as the
  authoritative many-to-one relationship from Feedback to Case.
- Keep `matched_case_id` only as a query shortcut.
- A Case may aggregate many Feedback records.
- Repeated promotion requests must return the existing Case and must not create
  duplicate Cases or evidence rows.

## Promotion transaction

Add `FeedbackCaseWorkflowService.promoteToCase(...)`:

1. Lock the Feedback by `feedbackId` and `agentId`.
2. Require status `VALID` or `CLUSTERED`.
3. Find the requested Case or create a new `PENDING_REVIEW` Case.
4. Insert idempotent Case evidence.
5. Update Feedback to `PROMOTED` and set `matched_case_id`.
6. Insert a review record for `FEEDBACK -> PROMOTED`.
7. Insert an audit record with actor, role, reason and before/after state.
8. Commit everything together; rollback on any failure.

The Controller should only validate the request and call the service.

## Backend API

```text
GET  /api/v1/agents/{agentId}/feedback?status=&sourceType=&category=&keyword=&page=&size=
GET  /api/v1/agents/{agentId}/feedback/{feedbackId}
POST /api/v1/agents/{agentId}/feedback/{feedbackId}/transition
POST /api/v1/agents/{agentId}/feedback/{feedbackId}/promote
GET  /api/v1/agents/{agentId}/feedback/{feedbackId}/timeline

GET  /api/v1/agents/{agentId}/cases?status=&owner=&severity=&keyword=&page=&size=
GET  /api/v1/agents/{agentId}/cases/{caseId}
POST /api/v1/agents/{agentId}/cases/{caseId}/transition
POST /api/v1/agents/{agentId}/cases/{caseId}/merge
GET  /api/v1/agents/{agentId}/cases/{caseId}/timeline
```

Add `IAuditLogDao` and `ICaseReviewRecordDao`. Add services
`FeedbackReviewService`, `FeedbackCaseWorkflowService` and
`CaseWorkflowService`.

## Frontend pages

### Feedback inbox

- Agent, status, source, category and keyword filters.
- List of feedback summaries with status, source, priority and time.
- Detail drawer with original message, session, AI suggestions, similar Cases,
  evidence and timeline.
- Actions: confirm, request information, reject, promote to Case.
- Promotion dialog: title, type, severity, reason and actor.

### Case workspace

- Separate navigation entry from Feedback.
- Default groups: pending review, confirmed, in progress, resolved and archived.
- Detail view: summary, severity, owner, source Feedback, evidence, score,
  timeline and resolution.
- Actions depend on status: confirm, assign, start, resolve, reopen, archive,
  merge.

### Dashboard

Keep only counters and links: pending Feedback, pending Cases, active Cases,
resolved Cases and recent promotions. Do not mix review operations into the
dashboard.

## Agent long-term profile

Long-term memory belongs to the Agent, not to an individual Case. A Case is
only evidence used to update the Agent profile.

```text
Case CONFIRMED/RESOLVED
  -> extract stable facts, rules, failure patterns and preferences
  -> merge with the Agent profile
  -> deduplicate and version the profile
  -> store Agent-scoped long-term memory
```

Rules:

- `CANDIDATE` and `PENDING_REVIEW`: never contribute to long-term memory.
- `CONFIRMED`: may contribute only when the reviewer explicitly enables it.
- `RESOLVED`: eligible for profile extraction after the resolution is verified.
- `ARCHIVED`: remains historical evidence; it is not automatically recalled.
- The memory record must contain `agent_id`, `source_case_id`, `memory_type`,
  `profile_version`, `confidence` and `source_status`.
- The recall scope must always include the current `agent_id`; one Agent must
  never retrieve another Agent's profile memory.
- Reprocessing the same Case must update the profile version instead of
  creating duplicate memories.

The Agent profile should contain structured sections rather than a raw list of
Cases:

- `capabilities`: verified capabilities and supported workflows.
- `failure_patterns`: recurring failure modes and their conditions.
- `business_rules`: confirmed rules and constraints.
- `resolution_patterns`: verified solutions and operating procedures.
- `preferences`: stable user or business preferences relevant to this Agent.

Add an explicit `AgentMemoryProfileService` for extraction, merge, conflict
resolution and versioning. The Case workflow should call this service after a
verified Case transition; it should not insert vector records directly.

The Agent profile update must be idempotent by `agent_id + source_case_id +
profile_version` and must create a Case timeline event with the extraction
result. Failed extraction must not make the Case transition fail; it should be
recorded as a retryable profile-update job.

## Implementation order

1. Add DAO and timeline queries; remove Controller SQL.
2. Add transaction services for review, promotion and Case transitions.
3. Add transition, idempotency and schema tests.
4. Split the Vue UI into Feedback and Case pages.
5. Add Agent-scoped profile extraction, versioning and dashboard counters.

## Acceptance criteria

- New feedback stays in the Feedback inbox and is not an approved Case.
- Only an authorized operator can review or promote.
- Repeated promotion is idempotent.
- Every transition has actor, time, reason and before/after status.
- A Case cannot start without an owner or resolve without a resolution.
- Unreviewed Cases never contribute to Agent long-term memory.
- Controllers contain no business SQL.
- Files remain UTF-8/ASCII-safe with no mojibake.
