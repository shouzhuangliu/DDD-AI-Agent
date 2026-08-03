# Case 证据门禁与业务 Skill 评测设计

## 1. 背景与目标

当前对话质量评测把“反馈采集、业务评测、Case 候选、人工发布”压缩在一次模型输出中。模型可以同时生成标题、摘要、分数和 `promoteToCase`，导致以下问题：

- 单字、测试、确认语句或普通聊天被误判为业务问题；
- 业务 Skill 虽然被注入，但模型没有引用具体规则，无法证明为什么与当前 Agent 相关；
- Case 摘要混入助手的推测和执行文案，不能追溯到用户/运维事实；
- 评分主要是模型自报，服务端缺少可复核的晋升门禁；
- “反馈还需要补充信息”和“已经形成 Case”没有稳定的终态。

本设计把模型定位为“事实抽取和候选建议器”，把准入、证据校验、摘要生成和状态流转放回服务端。目标是让每一个 Case 都能回答三个问题：

1. 它属于当前 Agent 的哪条业务 Skill 规则？
2. 哪些用户/运维/MCP 证据支持它？
3. 为什么现在可以进入人工审核，而不是继续补充信息？

## 2. 业界依据

- [Atlassian Incident Response Lifecycle](https://www.atlassian.com/incident-management/incident-response/lifecycle)：将发现、影响评估/严重度、响应、恢复和事后改进拆成不同阶段，不把检测信号直接等同于 Incident。
- [Atlassian Severity Levels](https://www.atlassian.com/incident-management/kpis/severity-levels)：严重度应由业务影响决定，影响范围、核心功能可用性和数据损失是判断依据。
- [Google SRE Postmortem Practices](https://sre.google/workbook/postmortem-culture/)：要求保留时间线、量化指标、原始来源、负责人和可验证的行动项，避免只保留自然语言结论。
- [ServiceNow Problem Management](https://www.servicenow.com/products/itsm/what-is-problem-management.html)：Problem 是一个或多个 Incident 的潜在原因，建议使用结构化调查和已知错误/临时方案沉淀，而不是把每个投诉都升级为 Problem。

这些原则映射到本项目后，Feedback 是输入事实，Case 是经过业务规则和证据门禁的候选 Problem，发布仍由人工审核完成。

## 3. 范围与非目标

### 本期范围

- 重构会话质量评测 JSON 契约；
- 为业务 Skill 增加规则 ID、证据要求、反例和缺失信息定义；
- 服务端校验 Skill 绑定关系、消息证据和 Case 晋升条件；
- 用结构化事实生成可追溯 Case 摘要；
- 保存评测快照和证据引用，支持审计与回放；
- 保留现有 Agent 维度、短期记忆、长期记忆和人工审核流程。

### 非目标

- 本期不引入多租户；
- 不让评测 Agent 自动执行生产变更；
- 不把长期记忆直接当作当前业务事实；
- 不改聊天页面的视觉布局；
- 不通过第二个大模型解决所有不确定性，后续可作为成本更高的增强方案。

## 4. 统一状态机

### 4.1 会话/反馈评测状态

```text
NOT_ELIGIBLE
    └─ 低价值输入、问候、测试、内部占位文案

FEEDBACK_CAPTURED
    └─ 识别到用户/运维反馈，但尚不足以形成 Case

NEED_MORE_INFO
    └─ 命中业务 Skill，但缺对象、实际结果、影响或可验证证据

CANDIDATE_CASE
    └─ 通过服务端证据门禁，等待人工审核

PENDING_REVIEW
    ├─ APPROVED/PUBLISHED
    ├─ REJECTED
    └─ DUPLICATE/MERGED
```

`NOT_ELIGIBLE` 不创建业务 Feedback；`FEEDBACK_CAPTURED` 和 `NEED_MORE_INFO` 只保留 Feedback，不创建 `ai_case`；只有 `CANDIDATE_CASE` 才允许创建候选 Case。模型不能跳过状态，服务端根据结构化结果重新计算最终状态。

### 4.2 Case 晋升门禁

服务端必须同时满足：

1. `skillId` 属于当前 Agent 的已绑定 Skill，且 SKILL.md 可读取；
2. 至少一个真实 `ruleId` 命中该 Skill；
3. 证据至少来自用户/运维原话，或来自绑定 MCP 的业务结果；助手自己的回答、思考和错误文案不能单独作为业务事实；
4. 结构化事实至少包含：业务对象、实际异常、业务影响；
5. 证据满足以下任一条件：
   - 一条明确的高影响反馈同时给出对象、实际结果和影响；
   - 两条独立消息/反馈指向同一业务问题；
   - 绑定 MCP 返回可验证的业务结果，并且用户反馈指向该结果；
6. 服务端计算的置信度、证据分和业务相关度达到策略阈值；模型返回的分数只能作为输入，不能单独决定晋升；
7. 没有命中重复 Case，或已产生明确的合并关系。

任何一项不满足，都只能进入 `FEEDBACK_CAPTURED` 或 `NEED_MORE_INFO`。

## 5. 模型评测契约

模型只返回纯 JSON，不生成 Markdown，不直接生成最终 Case 摘要：

```json
{
  "decision": "NOT_ELIGIBLE|FEEDBACK_ONLY|NEED_MORE_INFO|CANDIDATE_CASE",
  "skill": {
    "id": "inventory-feedback-agent",
    "ruleIds": ["inventory.stock-gap.v1"],
    "matchScore": 0
  },
  "facts": {
    "subject": "DDR5 内存商品",
    "expected": "商品应出现在可售列表",
    "actual": "反馈称商品缺失",
    "impact": "用户无法完成购买",
    "timeRange": "",
    "scope": ""
  },
  "evidence": [
    {
      "messageId": 123,
      "role": "user|operator|tool",
      "quote": "用户原话短摘录",
      "supports": ["subject", "actual", "impact"]
    }
  ],
  "missingInformation": ["SKU 或商品 ID"],
  "severity": "P0|P1|P2|P3",
  "confidence": 0,
  "reason": "中文评测理由"
}
```

约束：

- `decision=NOT_ELIGIBLE` 时 `evidence=[]`、`facts` 为空；
- `decision=CANDIDATE_CASE` 时 `skill.id`、`ruleIds`、`facts.subject/actual/impact` 和有效证据必须存在；
- `messageId` 必须属于当前会话，`quote` 必须能在原消息中匹配；
- `role=assistant` 的证据一律降级，不能支撑业务事实；
- `missingInformation` 非空时不能进入 `CANDIDATE_CASE`，除非命中高危规则且 Skill 明确允许单证据晋升；
- 未绑定 Skill、Skill 文档不可读或规则 ID 不存在时，服务端强制改为 `NEED_MORE_INFO`。

## 6. Skill 规则格式

入口 SKILL.md 继续采用渐进式加载；业务规则文件增加统一结构：

```markdown
## 规则 inventory.stock-gap.v1

- 业务对象：SKU、商品、可售库存
- 触发条件：商品应可售但查询结果缺失，或库存扣减后可售状态未同步
- 必需证据：SKU/商品 ID、查询范围、实际结果、影响
- 可接受来源：用户原话、运维反馈、inventory MCP 查询结果
- 不构成 Case：仅表达补货愿望但没有系统异常；没有商品对象或影响
- 严重度：阻断下单为 P1；单个商品缺失且有替代品为 P2
- 缺失信息：SKU、时间范围、页面/接口、影响范围
```

模型只加载当前阶段所需的规则文件，评测上下文中同时注入 `skillId`、`ruleId`、规则正文和版本号，便于审计。

## 7. 后端组件与数据流

```text
ChatMessage
  -> ConversationQualificationPolicy（低价值/业务反馈确定性准入）
  -> AnalysisJob（会话空闲后执行，保留 policy_version）
  -> AgentEvaluationContextBuilder（绑定 Skill + 规则 + 证据消息）
  -> LLM Structured Evaluator（只抽取 JSON）
  -> AnalysisResultParser（契约校验）
  -> CaseEvidenceGate（服务端重新计算）
       ├─ NOT_ELIGIBLE：结束
       ├─ FEEDBACK_ONLY/NEED_MORE_INFO：更新 Feedback 评测状态
       └─ CANDIDATE_CASE：保存证据快照、生成模板摘要、创建候选 Case
  -> 人工审核与发布
```

新增/调整组件：

- `CaseEvidenceGate`：纯 Java、无模型依赖，负责 Skill 绑定、消息归属、事实完整性、证据角色、重复性和阈值判断；
- `CaseSummaryComposer`：根据 `facts + rule + evidence` 生成模板化摘要；
- `CaseEvaluationSnapshot`：保存每次评测的 decision、规则、事实、缺失信息、证据 JSON、模型和策略版本；
- `CaseEvidence` 扩展证据角色、规则 ID 和证据支持字段，保留原始消息引用。

## 8. 数据库与兼容性

新增迁移 `V20260803__case_evidence_gate.sql`：

- 新建 `case_evaluation_snapshot`，以 `session_id + assistant_message_id + policy_version` 做幂等约束；
- `case_evidence` 增加 `evidence_role`、`skill_rule_id`、`supports_json`；
- 所有新字段提供默认值，兼容已有 Case 和历史数据；
- 历史 Case 不重跑模型，只在查看详情时标记为“历史数据/缺少结构化评测快照”。

## 9. 错误处理与安全边界

- LLM 返回非 JSON、字段缺失、消息 ID 越界或引用不匹配：评测任务失败并按现有重试策略处理，不创建 Case；
- Skill 读取失败：记录 `SKILL_CONTEXT_UNAVAILABLE`，不允许晋升；
- MCP 失败、超时、参数错误和模型限流只能生成运行观察 Signal，不能作为业务 Case 证据；
- 证据摘录限制长度并进行敏感字段脱敏；原始消息仍保留在聊天原件表，审计接口按 Agent 和会话隔离；
- 评测策略通过 `policy_version` 固化，避免同一会话重复执行产生不同规则结果而无法解释。

## 10. 测试与验收标准

### 单元测试

- `1`、`OK`、问候和内部执行文案：不创建 Feedback/Case；
- “DDR5 商品缺失，希望补货”：只能 `FEEDBACK_ONLY/NEED_MORE_INFO`，缺 SKU/影响时不能生成 Case；
- 同一会话补充 SKU、页面、实际结果和影响：命中库存 Skill 规则后进入 `CANDIDATE_CASE`；
- 未绑定库存 Skill、规则 ID 不存在、引用助手消息：强制降级；
- 证据摘录与原消息不匹配：解析失败或拒绝晋升；
- MCP 返回库存缺失且用户反馈指向该结果：允许通过门禁；
- 重复反馈：更新现有 Case 频次并写入证据，不产生重复 Case。

### 集成验收

- 一次完整对话能在日志中看到：准入、Skill 规则、模型 JSON、门禁结果、证据消息、摘要生成和人工审核状态；
- Case 详情可以反查到至少一条用户/运维原话和对应 Skill 规则；
- 评测任务重试和重复执行不会重复创建 Case；
- 现有 82 个 trigger 测试、30 个 app 测试和前端构建继续通过。

## 11. 演进路线

第一期采用单模型结构化抽取 + 确定性门禁。积累足够人工审核数据后，再评估增加独立 Judge 模型或规则学习，不把第二模型作为第一期的正确性兜底。
