# Agent Backend Runtime and Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the backend so Agent runtime capability loading and Feedback evaluation are precise, agent-scoped, and interview-grade.

**Architecture:** Reuse the existing Agent/Skill/Feedback skeleton, but tighten runtime root resolution for bound Skills and unify feedback business-signal logic across admission and evaluation. Keep the persisted state model stable while improving correctness and explainability.

**Tech Stack:** Spring Boot 3, MyBatis, Java 17+, JUnit 5, Mockito.

## Global Constraints

- Do not overwrite the user's local dirty `ai-agent-station-study-app/src/main/java/cn/bugstack/ai/Application.java`.
- Use TDD for behavior changes.
- Keep all capability boundaries agent-scoped.
- Do not weaken bound Skill isolation or tool allowlists.
- User-facing strings should remain Chinese where this phase touches them.
- Commit each coherent slice with a clear Chinese conventional commit message.

---

### Task 1: Runtime Skill Resolution Hardening

**Files:**

- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/skills/SkillScannerService.java`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/skill/SkillScannerRuntimePreferenceTest.java`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/SkillScannerServiceTest.java`

**Interfaces:**

- Consumes: `readSkillFromWorkDir(workDir, skillId)`, `readSkillMetadataFromWorkDir(workDir, skillId)`
- Produces: deterministic runtime root order: agent workspace `.ma/skills` first, then project `skills` roots fallback

- [x] Add/adjust failing tests for runtime workspace-first resolution and project fallback.
- [x] Run those tests to verify failure if behavior drifts.
- [x] Implement unified runtime root resolution.
- [x] Run the targeted skill-scanner tests.
- [x] Commit: `fix: 修正Agent运行时Skill装配路径`.

### Task 2: Shared Feedback Signal Model

**Files:**

- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAdmissionPolicy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/FeedbackEvaluationWorker.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/agent/AgentBusinessContextService.java`
- Modify tests:
  - `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAdmissionPolicyTest.java`
  - `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAutoCaptureServiceTest.java`
  - `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/FeedbackEvaluationWorkerTest.java`

**Interfaces:**

- Consumes: user message, agent-scoped business keywords
- Produces: aligned business-signal decisions for admission and evaluation

- [x] Add/adjust failing tests for specialized skill-domain feedback and noise rejection.
- [x] Run targeted tests to confirm red state where needed.
- [x] Implement shared signal logic and tighten evaluation semantics.
- [x] Run targeted feedback tests.
- [x] Commit: `feat: 统一Agent业务反馈评测规则`.

### Task 3: Regression Verification and Handoff

**Files:**

- No production file target; verification and docs only

**Interfaces:**

- Consumes: route policy, controller binding behavior, feedback pipeline tests
- Produces: verified backend slice ready for later state-machine expansion

- [x] Run key regression suite covering route, controller binding, feedback, and skill runtime behavior.
- [x] Update this plan’s checkboxes to reflect completion.
- [ ] Commit: `docs: 更新后端装配与反馈治理阶段状态`.
- [ ] Push to `origin/main`.
