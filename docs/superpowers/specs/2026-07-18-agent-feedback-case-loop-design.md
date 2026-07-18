# Agent 业务反馈与 Case 闭环设计

## 目标

把项目从“普通对话自动提取 Case”调整为“以 Agent 为业务单元的反馈评测与 Case 发布系统”。每个业务 Agent 绑定自己的模型、Soul.md、Skills、MCP 和记忆策略；系统从用户反馈、运维反馈、测试反馈和高质量对话证据中提取候选 Feedback，再经过 AI 评测、聚类、评分和人工审核升级为 Case，最后发布到看板等待开发或运维处理。

## 核心原则

普通对话不是 Feedback，AI 观察不是 Case。Case 必须代表某个 Agent 在业务场景中出现的可复盘、可处理、可追踪的问题或经验。

系统必须先判断输入是否属于当前 Agent 的业务范围，再判断是否形成 Feedback，最后才判断是否升级为 Case。`1`、`OK`、`继续`、`好的`、普通问候、内部执行占位文案等低价值输入不能进入 Case 流程。

## 业务对象

### Agent

Agent 是业务单元。每个 Agent 至少包含：

- agent_id
- 业务名称和业务范围
- 默认模型与可切换模型
- Soul.md
- 绑定的业务 Skills
- 绑定的 MCP
- 短期记忆策略
- 长期记忆召回策略

### Signal

Signal 是系统自动观察到的低置信度线索，例如工具失败、重复提问、答非所问、用户纠错、MCP 超时。Signal 不等于 Feedback，也不能直接发布到 Case 看板。

### Feedback

Feedback 是明确反馈，来源包括用户、运维、测试人员和被准入策略认可的 AI 观察。Feedback 必须绑定 agent_id、session_id、message_id、source_type、feedback_type、severity、evidence。

### Evaluation

Evaluation 是大模型评测结果，负责判断：

- 是否与当前 Agent 业务相关
- 是否暴露问题或改进机会
- 是否匹配历史 Feedback 或 Case
- 是否证据充分
- 是否建议升级为候选 Case

### Case

Case 是经过聚类和评测后的问题资产。Case 必须包含所属 Agent、业务项目/场景、类型、严重程度、影响范围、证据链、AI 归因、建议处理方案、审核状态和发布状态。

## 流程

```text
Agent 配置
  ↓
用户/运维/测试与 Agent 交互
  ↓
短期记忆记录当前会话
  ↓
长期记忆召回历史 Case、Feedback、业务规则、Skills/MCP 使用经验
  ↓
准入策略过滤低价值输入
  ↓
大模型做业务相关性和问题识别
  ↓
生成 Feedback 候选或合并到已有 Feedback
  ↓
评分判断是否升级为候选 Case
  ↓
人工审核
  ↓
发布到 Case 看板
  ↓
开发/运维处理
  ↓
处理结论反哺长期记忆、Soul.md、Skills、MCP 测试用例
```

## 准入与升级门槛

分析准入必须满足以下任一条件：

- 存在显式负反馈
- 存在运维或测试反馈
- 对话达到有效业务长度和轮次，并且不是低价值输入

Case 升级必须满足：

- 业务相关度不低于 70
- 问题置信度不低于 75
- 证据完整度不低于 60
- 并且满足以下任一条件：
  - 用户显式负反馈
  - 运维或测试显式反馈
  - 同类问题跨至少两个会话出现
  - 严重程度为 HIGH 或 CRITICAL
  - 命中长期记忆中的高危历史 Case

## 状态机

Feedback 状态：

```text
新反馈 → AI评测中 → 有效反馈/无效反馈/证据不足 → 已归类 → 已升级候选Case
```

Case 状态：

```text
候选Case → 待审核 → 已发布 → 处理中 → 已解决 → 已归档
```

补充状态：

```text
已拒绝 / 已合并 / 重复Case / 已忽略
```

## 长短期记忆协作

短期记忆用于当前会话理解，包含当前目标、任务进度、最近消息、会话摘要和工具调用结果。

长期记忆用于业务经验沉淀，包含历史 Case、已解决问题、高频 Feedback、业务规则、运维纠错、Skills 使用经验、MCP 调用失败经验。

每次 Evaluation 前，应按 agent_id 和当前语义召回长期记忆，并把召回结果作为评测证据，而不是直接让模型只看当前一句话。

## 前端信息架构

页面按 Agent 组织：

- Agent 配置：模型、Soul.md、Skills、MCP、记忆策略
- Agent 对话：历史会话、当前短期记忆、相关长期记忆
- Feedback 中心：用户反馈、运维反馈、测试反馈、AI 观察
- Case 中心：候选 Case、待审核 Case、已发布 Case、已解决 Case
- 质量看板：高优先级 Case、Skill 缺口、MCP 异常、Agent 质量趋势

所有页面文案使用中文，避免把内部英文枚举直接暴露给中文用户。
