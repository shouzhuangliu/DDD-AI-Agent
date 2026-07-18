# Agent Feedback Case Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Agent-scoped Feedback → Evaluation → Case workflow that prevents casual chat from becoming Cases and makes Case publication depend on business relevance, evidence, and review state.

**Architecture:** Keep the current async analysis worker, but add a stricter domain policy before LLM analysis and before Case promotion. The worker will eventually enrich prompts with Agent profile, Skills/MCP metadata, short-term summary, and long-term recalled memory; the first implementation focuses on safe admission and promotion thresholds.

**Tech Stack:** Java 17, Spring Boot, MyBatis, JUnit 5, Maven, MySQL, pgvector/SiliconFlow BGE-M3 for long-term memory.

## Global Constraints

- Single-tenant for now; isolate data by `agent_id`.
- Ordinary chat messages are not Feedback.
- AI Signals are observation records and cannot directly become published Cases.
- Cases must be candidates first and require human review before publishing.
- Low-value inputs such as `1`, `OK`, `继续`, `好的`, greetings, and internal execution placeholders must not enter the Case pipeline.
- UI copy must be Chinese.

---

### Task 1: Strengthen conversation admission and Case promotion policy

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationQualificationPolicy.java`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/ConversationQualificationPolicyTest.java`

**Interfaces:**
- Produces: `ConversationQualificationPolicy.shouldAnalyze(List<ConversationMessage>, int explicitNegativeFeedback)`
- Produces: `ConversationQualificationPolicy.shouldPromoteCase(CasePromotionInput input)`

- [x] Write failing tests for low-value Chinese and English short replies.
- [x] Run the focused JUnit test and confirm the new assertions fail.
- [x] Add low-value input detection and stronger promotion input model.
- [x] Run the focused JUnit test and confirm it passes.

### Task 2: Add Agent-scoped evaluation thresholds

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AnalysisResultParser.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java`
- Modify: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/AnalysisResultParserTest.java`

**Interfaces:**
- Produces: parser fields `businessRelated`, `businessRelevance`, `evidenceScore`, and `promoteToCase`
- Consumes: `ConversationQualificationPolicy.CasePromotionInput`

- [x] Write parser tests for missing optional evaluation fields defaulting safely.
- [x] Run parser tests and confirm failure where new fields are expected.
- [x] Extend parser records and worker promotion call.
- [x] Run parser and qualification tests.

### Task 3: Make AI prompt match Feedback/Evaluation/Case semantics

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java`

**Interfaces:**
- Consumes: `AnalysisResultParser.CaseCandidate`
- Produces: stricter JSON contract for business relevance and evidence score.

- [x] Update system prompt to say ordinary conversation can only create signals, Feedback needs explicit evidence, and Case needs business relevance.
- [x] Verify parser accepts the prompt contract with focused tests.

### Task 4: Chinese dashboard copy and Case semantics

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: existing dashboard endpoints.
- Produces: Chinese labels for Feedback, Signal, Case, review, publish, and Agent quality sections.

- [ ] Replace English status labels shown in Case details with Chinese labels.
- [ ] Rename dashboard sections to Agent 业务反馈、候选 Case、待审核 Case、已发布 Case.
- [ ] Manually verify static references with `rg`.

### Task 5: Verification

**Files:**
- Read: `README.md`
- Run: Maven focused tests

- [x] Run `mvn -pl ai-agent-station-study-app -am -DskipTests=false "-Dtest=ConversationQualificationPolicyTest,AnalysisResultParserTest,CaseScoringServiceTest,WorkflowTransitionPolicyTest" -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] Run `git status --short`
- [ ] Report exact verification output and remaining gaps.
