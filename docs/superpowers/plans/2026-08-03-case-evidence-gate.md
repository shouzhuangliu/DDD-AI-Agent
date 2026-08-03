# Case 证据门禁与摘要频率 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将会话中的业务 Feedback 通过可审计的 Skill 规则、消息证据和摘要频率门禁，稳定地评测为候选 Case，而不是由模型直接自由生成 Case。

**Architecture:** 保留现有会话空闲队列和人工审核工作流，在评测层新增结构化 `CaseEvaluation` 契约、纯 Java `CaseEvidenceGate` 和模板化 `CaseSummaryComposer`。模型只抽取事实与证据，服务端验证 Skill 绑定、消息归属、事实完整性、新增证据指纹和晋升阈值；每次结果保存评测快照，CaseEvidence 保存原始消息引用。

**Tech Stack:** Java 21、Spring Boot、MyBatis、MySQL 8、JUnit 5、Fastjson2、现有 Spring AI ChatClient。

## Global Constraints

- 不引入多租户，不让评测链路执行生产变更。
- `NOT_ELIGIBLE`、`FEEDBACK_ONLY`、`NEED_MORE_INFO` 不创建 `ai_case`。
- Case 必须绑定当前 Agent 可读取的 Skill 和真实 `ruleId`，助手消息不能独立作为业务事实。
- Case 摘要只能由结构化事实、Skill 规则和证据引用生成；模型摘要只能作为评测理由。
- 保留会话空闲 60 秒与 5 分钟去重窗口；首次评测默认至少 2 个有效用户/运维回合，后续至少新增 2 条业务证据。
- 短期记忆保留最近 24 条消息，至少 4 个新业务用户回合且超过 8,000 token 才滚动总结，16,000 token 为硬上限。
- 任何任务完成前必须运行 trigger/app 测试、打包和前端构建；提交信息使用中文 Conventional Commit。

---

### Task 1: 固化评测与摘要触发频率策略

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseAnalysisCadencePolicy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AnalysisJobQueue.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/ShortTermMemoryService.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/memory/RollingSummaryPolicy.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/CaseAnalysisCadencePolicyTest.java`
- Test: `ai-agent-station-study-domain/src/test/java/cn/bugstack/ai/domain/agent/service/memory/RollingSummaryPolicyTest.java`

**Interfaces:**
- `CaseAnalysisCadencePolicy.shouldEvaluate(List<ConversationMessage> messages, EvaluationCursor cursor, boolean explicitFeedback, boolean newMcpEvidence)` returns `Decision(required, reason, evidenceFingerprint)`.
- `CaseAnalysisCadencePolicy.countMeaningfulUserTurns(List<ConversationMessage>)` counts non-empty user/operator messages after low-value filtering.
- `RollingSummaryPolicy` gains `minNewMeaningfulUserTurns` and only returns `required=true` when the existing token/hard-limit rules and the new-turn rule are satisfied, except for the hard limit.

- [ ] **Step 1: Write failing cadence tests**

```java
@Test
void doesNotReevaluateWithoutTwoNewBusinessEvidenceMessages() {
    var messages = List.of(user("库存缺失，商品 DDR5 无法下单"), assistant("已记录"));
    var cursor = new CaseAnalysisCadencePolicy.EvaluationCursor(1, 1, "fp-1");
    assertFalse(policy.shouldEvaluate(messages, cursor, false, false).required());
}

@Test
void explicitFeedbackCanEvaluateEarlyButStillUsesEvidenceGateLater() {
    var result = policy.shouldEvaluate(List.of(user("商品缺失")),
            new CaseAnalysisCadencePolicy.EvaluationCursor(0, 0, ""), true, false);
    assertTrue(result.required());
}
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run: `mvn -pl ai-agent-station-study-trigger,ai-agent-station-study-domain -am -Dtest=CaseAnalysisCadencePolicyTest,RollingSummaryPolicyTest test`

Expected: FAIL because the cadence type and meaningful-turn threshold do not exist.

- [ ] **Step 3: Implement the policy and wire existing queue constants**

Use `IDLE_DELAY=60s` and `SESSION_DEBOUNCE_WINDOW=5m` as scheduling constraints. The policy must ignore `1`, `OK`, greetings, “继续”, “好的” and internal execution placeholders. Compute a stable SHA-256 fingerprint from sorted new evidence message IDs and normalized content; identical fingerprints return `required=false`.

- [ ] **Step 4: Implement the short-term memory threshold**

Pass `minNewMeaningfulUserTurns=4` into `RollingSummaryPolicy`. A token count above 16,000 bypasses the turn threshold; otherwise require at least 4 new meaningful user/operator turns and 8,000 estimated tokens. Keep `retainMessages=24`.

- [ ] **Step 5: Run targeted tests and commit**

Run the commands from Step 2 and the existing `ConversationQualificationPolicyTest`. Expected: PASS.

```bash
git add ai-agent-station-study-trigger ai-agent-station-study-domain
git commit -m "feat: 增加Case评测与记忆摘要频率门禁"
```

### Task 2: 增加结构化 CaseEvaluation 契约与兼容解析

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AnalysisResultParser.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/AnalysisResultParserStructuredTest.java`

**Interfaces:**
- `AnalysisResultParser.parse(String raw)` returns an `AnalysisResult` that includes `decision`, `SkillMatch`, `FactSet`, `EvidenceCandidate`, `missingInformation`, `severity`, `confidence`, `reason` and existing runtime `signals`.
- The parser accepts the new structured JSON and the current legacy `{signals,cases}` JSON during rollout; legacy cases are marked `LEGACY_UNVERIFIED` so the gate cannot promote them without structured evidence.

- [ ] **Step 1: Write failing parser tests**

Cover valid `NEED_MORE_INFO`, valid `CANDIDATE_CASE`, missing `ruleIds`, out-of-range scores, assistant-only evidence, invalid decision, and legacy JSON downgrade. Assert that evidence records retain `messageId`, `role`, `quote` and `supports`.

- [ ] **Step 2: Run targeted parser tests and verify failure**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=AnalysisResultParserStructuredTest test`

Expected: FAIL because structured records and fields are absent.

- [ ] **Step 3: Implement strict JSON parsing**

Use Fastjson2. Enforce plain JSON, valid enum values, scores in `[0,100]`, evidence quote length <= 500, no blank `ruleIds` for candidate decisions, and no evidence for `NOT_ELIGIBLE`. Preserve existing signal parsing and localization.

- [ ] **Step 4: Run parser and existing qualification tests**

Expected: PASS, including the existing legacy business-evidence tests.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AnalysisResultParser.java ai-agent-station-study-trigger/src/test
git commit -m "feat: 引入结构化Case评测契约"
```

### Task 3: 实现服务端 CaseEvidenceGate

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseEvidenceGate.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/CaseEvidenceGateTest.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationQualificationPolicy.java`

**Interfaces:**
- `CaseEvidenceGate.evaluate(String agentId, List<ChatMessage> messages, AnalysisResultParser.AnalysisResult evaluation, BoundSkillContext skillContext, ExistingCaseContext existing)` returns `GateDecision(state, acceptedEvidence, missingInformation, serverScore, reason, evidenceFingerprint)`.
- `BoundSkillContext` contains only Skill IDs, versioned rule IDs, and readable rule definitions assembled for the current Agent.

- [ ] **Step 1: Write failing gate tests**

Test the exact business examples: `1` is `NOT_ELIGIBLE`; “DDR5 商品缺失，希望补货” is `NEED_MORE_INFO`; a complete DDR5 object/expected/actual/impact with a matching bound rule becomes `CANDIDATE_CASE`; unbound Skill, nonexistent rule, assistant-only evidence, mismatched quote, runtime failure and missing impact are rejected; two independent user messages are accepted; duplicate evidence fingerprint is a no-op.

- [ ] **Step 2: Run gate tests and verify failure**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=CaseEvidenceGateTest test`

Expected: FAIL because `CaseEvidenceGate` does not exist.

- [ ] **Step 3: Implement deterministic gate**

Validate every `messageId` belongs to the session, compare normalized quote against the original message, reject `assistant` as sole evidence, require `subject/actual/impact`, require bound `skillId/ruleId`, and require either one explicit high-impact complete report or two independent evidence messages. Derive the final server score from evidence count, field completeness, rule match and impact; never trust `promoteToCase`.

- [ ] **Step 4: Run gate, policy and parser tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseEvidenceGate.java ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationQualificationPolicy.java ai-agent-station-study-trigger/src/test
git commit -m "feat: 增加Case服务端证据门禁"
```

### Task 4: 模板化生成可信 Case 摘要

**Files:**
- Create: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseSummaryComposer.java`
- Create: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/CaseSummaryComposerTest.java`

**Interfaces:**
- `CaseSummaryComposer.compose(CaseEvaluation evaluation, BoundSkillRule rule, List<EvidenceRef> evidence)` returns `ComposedCase(title, summary, extractionReason)`.

- [ ] **Step 1: Write failing composer tests**

Assert the summary contains subject, expected, actual, impact, `skillId`, `ruleId` and evidence message IDs; missing facts return a non-promotable explanation instead of invented text; assistant prose is not copied as business fact.

- [ ] **Step 2: Run targeted tests and verify failure**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=CaseSummaryComposerTest test`

- [ ] **Step 3: Implement deterministic Chinese templates**

Generate `标题：{subject} 的 {actual}` and labeled summary sections. Limit each evidence excerpt to 500 characters and include source IDs in `extractionReason`.

- [ ] **Step 4: Run tests and commit**

```bash
git add ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseSummaryComposer.java ai-agent-station-study-trigger/src/test
git commit -m "feat: 生成可追溯Case结构化摘要"
```

### Task 5: 保存评测快照并扩展证据字段

**Files:**
- Create: `ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260803__case_evidence_gate.sql`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/CaseEvaluationSnapshot.java`
- Create: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/ICaseEvaluationSnapshotDao.java`
- Create: `ai-agent-station-study-app/src/main/resources/mybatis/mapper/case_evaluation_snapshot_mapper.xml`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/CaseEvidence.java`
- Modify: `ai-agent-station-study-app/src/main/resources/mybatis/mapper/case_evidence_mapper.xml`
- Modify: `ai-agent-station-study-app/src/main/resources/sql/mysql/create_tables.sql`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/contract/EnterpriseSchemaContractTest.java`

**Interfaces:**
- `ICaseEvaluationSnapshotDao.insertIgnore(CaseEvaluationSnapshot snapshot)` stores one immutable evaluation per idempotency key.
- `CaseEvidence` adds `evidenceRole`, `skillRuleId`, `supportsJson` with backward-compatible defaults.

- [ ] **Step 1: Add migration and schema contract test**

Create `case_evaluation_snapshot` with `agent_id`, `session_id`, `assistant_message_id`, `policy_version`, `decision`, `skill_id`, `rule_ids_json`, `facts_json`, `missing_information_json`, `evidence_json`, `confidence`, `server_score`, `reason`, `evidence_fingerprint`, `created_at`, and a unique idempotency key. Extend `case_evidence` with the three fields and indexes.

- [ ] **Step 2: Run schema test and verify failure**

Run: `mvn -pl ai-agent-station-study-trigger -am -Dtest=EnterpriseSchemaContractTest test`

Expected: FAIL until the migration/create-table contract is present.

- [ ] **Step 3: Add PO, DAO and MyBatis mappings**

Use the existing `insertIgnore` pattern, preserve old evidence rows, and default missing new fields to empty strings/JSON arrays.

- [ ] **Step 4: Run schema and mapper compilation tests; commit**

```bash
git add ai-agent-station-study-app/src/main/resources/sql ai-agent-station-study-app/src/main/resources/mybatis ai-agent-station-study-infrastructure/src/main/java
git commit -m "feat: 持久化Case评测快照与证据引用"
```

### Task 6: 接入 Worker、Skill 规则和业务上下文

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorker.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AgentEvaluationContextBuilder.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AnalysisJobQueue.java`
- Modify: `skills/inventory-feedback-agent/SKILL.md`
- Modify: `skills/inventory-feedback-agent/references/02-inventory-classification.md`
- Modify: `skills/inventory-feedback-agent/references/04-case-promotion.md`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/ConversationAnalysisWorkerTest.java`

**Interfaces:**
- Worker calls `CaseAnalysisCadencePolicy`, `AnalysisResultParser`, `CaseEvidenceGate`, `CaseSummaryComposer`, and snapshot DAO in that order.
- `AgentEvaluationContextBuilder` emits `[BOUND BUSINESS SKILL RULES]` with `skillId`, `version`, `ruleId`, rule body, and allowed evidence sources.

- [ ] **Step 1: Add worker integration tests**

Use mocks to assert `NOT_ELIGIBLE` never calls Case persistence, `NEED_MORE_INFO` saves a snapshot without `ai_case`, and `CANDIDATE_CASE` persists only user/tool evidence, template summary and one snapshot. Assert repeated fingerprint is skipped.

- [ ] **Step 2: Update the system prompt**

Replace the current `signals/cases/promoteToCase` contract with the structured `decision/skill/facts/evidence/missingInformation` contract. Explicitly forbid Markdown, assistant-only evidence, invented SKU/impact, runtime-fault Cases and final summaries from the model.

- [ ] **Step 3: Integrate gate and composer**

Use the gate’s final state and score. Persist a candidate Case only for `CANDIDATE_CASE`; leave existing manual transition APIs intact. For legacy parser output, persist a snapshot as `LEGACY_UNVERIFIED` and do not promote.

- [ ] **Step 4: Update inventory Skill rules**

Add `inventory.stock-gap.v1`, `inventory.stock-consistency.v1` and `inventory.replenishment-request.v1`, each with trigger, required evidence, non-Case examples, severity and missing-information fields. Keep one reference file per business point and progressive loading.

- [ ] **Step 5: Run targeted worker/Skill tests and commit**

```bash
git add ai-agent-station-study-trigger skills/inventory-feedback-agent
git commit -m "feat: 接入Skill规则驱动的Case评测链路"
```

### Task 7: 全链路验证与运行文档

**Files:**
- Modify: `docs/inventory-feedback-agent-quickstart.md`
- Modify: `docs/local-development.md`
- Test: all existing Maven and frontend tests

- [ ] **Step 1: Run trigger and app tests**

Run: `mvn -pl ai-agent-station-study-app -am -DskipTests=false test`

Expected: all existing tests plus new tests pass; no schema contract failure.

- [ ] **Step 2: Package and build frontend**

Run: `mvn -pl ai-agent-station-study-app -am -DskipTests package` and `npm run build` in `frontend-vue`.

- [ ] **Step 3: Execute deterministic smoke scenarios**

Verify: `1` produces no Feedback/Case; one vague DDR5 message stays `NEED_MORE_INFO`; a complete two-turn inventory issue creates one `CANDIDATE` with message IDs/rule ID; repeating the same messages does not create a second summary version.

- [ ] **Step 4: Commit and push**

```bash
git add docs
git commit -m "docs: 补充Case证据门禁运行验收说明"
git push origin main
```

