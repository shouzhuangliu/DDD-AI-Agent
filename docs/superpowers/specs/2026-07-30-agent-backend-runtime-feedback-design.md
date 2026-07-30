# Agent Backend Runtime and Feedback Governance Design

## Goal

Turn the current prototype into an interview-grade backend by tightening two core backend capabilities:

1. Agent runtime capability assembly must be deterministic.
2. Feedback ingestion and evaluation must be precise, agent-scoped, and business-aware.

This phase intentionally avoids frontend redesign and focuses on code that can be defended in a backend interview.

## Scope

This phase only changes backend runtime behavior and backend domain rules around:

- Agent-bound Skills runtime loading
- Agent-bound MCP/tool capability exposure
- Feedback admission
- Feedback evaluation
- Agent business-context extraction

## Problems To Solve

### 1. Skill runtime binding is not deterministic enough

The current code has two different ideas of runtime skill roots:

- metadata scanning checks both `skills/` and `.ma/skills/`
- runtime reads prefer `.ma/skills/` only

That creates a real mismatch:

- UI can show a Skill exists
- Agent runtime can still fail to read it
- the model may claim a Skill is missing even though it was bound

### 2. Feedback rules are duplicated and drift apart

`FeedbackAdmissionPolicy` and `FeedbackEvaluationWorker` both contain their own heuristic rules.

This leads to:

- inconsistent thresholds
- noisy feedback capture
- business-feedback routing not sharing the same evidence model as evaluation
- harder reasoning about why a message became feedback or not

### 3. Agent business context is present but not authoritative enough

The project already extracts business keywords from:

- agent name
- agent description
- bound Skill metadata

But the admission and evaluation layers still treat that context as a loose hint instead of the main business boundary.

The backend should behave as if:

- only bound Skills define business domain scope
- only bound MCPs define external action scope
- only explicit tool bindings define active execution privileges

## Backend Design

### A. Runtime capability assembly

Introduce one explicit backend rule:

`Agent runtime workspace > project runtime workspace > configured skills root`

Meaning:

1. First read from the agent workspace synchronized by `AgentWorkspaceService`.
2. If not found, allow fallback to project-level runtime roots only when this does not break binding isolation.
3. Keep runtime preference deterministic and testable.

This does not mean unbound Skills become readable. Binding checks still happen in controller/service boundaries before file reads are exposed.

### B. Shared business feedback rule model

Create one reusable backend policy model for feedback quality:

- problem signal
- business object signal
- evidence signal
- agent business context signal
- noise/test signal

Admission and evaluation should consume the same signal language instead of each embedding its own vocabulary and thresholds.

### C. Agent-scoped feedback governance

Feedback should only become a valid business feedback candidate when at least one of these is true:

- it contains a clear business object and evidence
- it matches the agent’s bound business context and contains a clear problem signal

Messages like:

- greetings
- acknowledgements
- tiny inputs
- vague repair wishes without business anchor

must not enter the real business-feedback pipeline.

### D. Stronger state semantics

For this phase, keep existing persisted states but tighten their meaning:

- `OPEN`: accepted as real feedback and waiting for evaluation
- `VALID`: concrete business issue with enough evidence
- `NEED_MORE_INFO`: plausible business issue but missing enough evidence
- `INVALID`: not a real business feedback

This keeps compatibility while making state interpretation much clearer.

## Implementation Units

### 1. Skill runtime resolution hardening

Files:

- `SkillScannerService`
- existing runtime preference tests

Work:

- unify runtime root resolution
- preserve preference order
- preserve bound-skill isolation
- add regression coverage for IDEA submodule startup and workspace-first resolution

### 2. Shared feedback signal policy

Files:

- `FeedbackAdmissionPolicy`
- `FeedbackEvaluationWorker`
- `AgentBusinessContextService`
- tests around auto capture and evaluation

Work:

- centralize problem/business/evidence/noise signals
- reduce duplicated heuristics
- make agent business keywords a first-class input
- keep behavior stable for valid existing cases while rejecting noise more consistently

## Non-goals

- no frontend redesign
- no new memory storage engine in this phase
- no full MCP registry redesign in this phase
- no multi-tenant expansion in this phase
- no replacement of current ReAct execution engine in this phase

## Acceptance Criteria

- Bound Skills can be read reliably from runtime workspace during agent execution.
- Runtime skill resolution prefers synchronized workspace copies over project-global skills.
- Feedback admission and feedback evaluation use aligned business-signal logic.
- Tiny inputs and vague chatter do not become business feedback.
- Skill-scoped domain feedback can be accepted even when the business domain is specialized.
- Existing route and capability tests keep passing.
