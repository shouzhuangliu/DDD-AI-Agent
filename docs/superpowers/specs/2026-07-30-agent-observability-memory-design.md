# Agent 运行观测与长期记忆设计

## 目标

把日志和记忆从“能看到记录”升级为“能讲清一次 Agent 为什么这么做、调用了什么能力、产生了什么业务资产”。本阶段优先服务简历展示和真实产品可用性：执行链路清晰、工具调用可审计、长期记忆有来源、有边界、可召回。

## 设计选择

采用“事件时间线 + 长期记忆画像”方案，不做完整 OpenTelemetry 级 tracing。原因是当前项目核心是 Agent 业务反馈治理，过重的 trace/span 基建会稀释主线；但结构化时间线和记忆画像足够体现专业度。

## 运行轨迹 Timeline

每个会话输出统一时间线：

1. 用户消息。
2. 路由事件：chat、feedback、react、plan。
3. 模型调用：模型、模式、耗时、token、状态、错误。
4. 工具调用：工具名、来源、入参、出参摘要、耗时、成功/失败。
5. Todo：计划项、状态。
6. Subagent：子任务、状态、结果。
7. Assistant 回复。
8. Feedback / Case 关联。

第一阶段不新增重型事件表，先由 `chat_message`、`ai_llm_log`、`agent_execution`、`memory_tool_result`、`subagent_task`、`ai_feedback`、`ai_case` 聚合成 `ConversationTrace` 视图对象。后续如果需要审计不可变事件流，再沉淀 `execution_timeline_event` 表。

## 工具调用结构化

工具调用统一展示：

- `toolName`
- `toolSource`: `BUILTIN / SKILL / MCP / SUBAGENT`
- `authorized`: 是否来自显式绑定或绑定 Skill/MCP 派生
- `input`
- `outputPreview`
- `durationMs`
- `status`
- `errorMessage`
- `messageId`
- `executionId`

已有数据不足时字段允许为空，但接口必须稳定返回这些键。

## 长期记忆

长期记忆按 Agent 隔离，来源分三类：

- `SESSION_SUMMARY`: 会话摘要。
- `CASE_PROFILE`: 已确认/已解决 Case 形成的业务经验。
- `FEEDBACK_PATTERN`: 多次有效反馈形成的问题模式。

写入规则：

- 未确认候选 Case 不进入长期记忆。
- 必须带 `agentId`、`sessionId/sourceCaseId/sourceFeedbackId`。
- 向量召回必须按 `agentId` 过滤。

第一阶段实现“可召回摘要接口 + 仪表盘展示”；pgvector 已有基础时接入真实向量检索，否则保留降级为最新画像/摘要检索。

## 前端体验

日志页改为企业级三段式：

- 左侧：Agent / 会话筛选。
- 中间：会话列表与调用摘要。
- 右侧：Timeline 详情。

Timeline 卡片按类型展示不同样式，长 JSON 折叠，失败事件高亮，Feedback/Case 可跳转来源。

## 测试策略

- 后端聚合服务用单元测试覆盖排序、分组、工具事件、Feedback/Case 关联。
- 长期记忆召回用单元测试覆盖 agent 隔离和降级逻辑。
- 前端变更执行 `npm run build`。
- 每个阶段小步提交并 push。
