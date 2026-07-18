# Agent 资产与会话连续性设计

## 目标

在单用户、按 Agent 隔离的前提下，把 Agent 的运行资产、长期会话和运营证据连成可追溯闭环。用户刷新或下次打开页面后，可从 Agent 下的历史会话继续对话；每个会话使用后端生成的 UUID。

## 已确认的范围

- 不实现多租户、账号、组织或权限系统。
- 每个 Agent 有一个可运行的默认模型，模型来自已配置的模型目录；聊天时仍可临时切换模型。
- 每个 Agent 的 Soul 以 Markdown 版本保存，只有已启用版本进入运行时系统提示词。
- Agent 只能绑定已发布的 Skill Release 和 MCP Release。
- Case 与显式 Feedback 必须能定位到 Agent、会话 UUID 和原始消息。

## 方案选择

选择“Agent 资产化 + 会话为主线”的中等复杂度方案。它在现有 `ai_agent`、`ai_session`、`chat_message`、记忆和运营表基础上扩展，不加入多用户边界。

## 数据模型

`ai_session` 保持一个会话一个 UUID，新增会话模型、最近消息时间和摘要预览。创建接口忽略客户端传入的会话 ID，统一由服务端 `UUID.randomUUID()` 生成。会话必须属于现有 Agent，发送消息前验证 Agent 与会话归属一致。

新增 `agent_soul_version`：保存 `agent_id`、版本号、Markdown 内容、SHA-256、状态、创建和启用时间。每个 Agent 同时最多一个 `ACTIVE` Soul；启用新版本会原子地停用旧版本，并同步 `ai_agent.system_prompt` 作为现有 Auto/ReAct 执行链的兼容运行配置。

`chat_message` 是会话原文；`memory_summary`、`memory_state`、`memory_tool_result` 均由同一 `session_id` 关联。折叠不删除原文，因此旧会话可完整展示，运行时则继续使用摘要加未覆盖消息。

`ai_feedback` 已存 `agent_id`、`session_id`、`assistant_message_id`；`case_evidence` 已存 `agent_id`、`session_id`、`message_id`。详情 API 将这些字段扩展为可直接导航的来源对象，并验证消息归属。

## 服务与 API

- `POST /api/v1/agents/{agentId}/sessions`：创建 UUID 会话并返回完整会话对象。
- `GET /api/v1/agents/{agentId}/sessions`：按最近活动时间获取历史会话。
- `GET /api/v1/agents/{agentId}/sessions/{sessionId}`：返回会话、完整可见消息、当前记忆摘要和来源关联信息。
- `POST /api/v1/agents/{agentId}/souls`：保存新的 Markdown 版本；`POST .../souls/{version}/activate`：启用指定版本；`GET .../souls`：读取版本历史及当前版本。
- Agent 编辑接口验证默认模型必须在模型目录中且凭证已配置；能力绑定继续通过现有 Release 生命周期校验。
- Case 与 Feedback 详情返回 `source`，其值包含 `agentId`、`sessionId`、`messageId` 和消息摘要。

## 前端交互

Agent 卡片进入“对话历史”，历史项显示标题、UUID 简写、消息数、最后活动时间和摘要。新对话调用后端创建接口；点击历史项调用详情接口渲染已存消息；随后发送仍使用同一 UUID。

Agent 编辑弹窗使用模型下拉框，展示已配置的 DeepSeek 和 SenseNova；增加 Soul 管理入口以及已发布 Skill/MCP 绑定摘要。Case 排行和 Feedback 列表的来源按钮跳转到 Agent 的指定会话，并在目标消息处高亮。

## 错误处理与一致性

- 非 UUID、未知会话、跨 Agent 会话和跨 Agent 消息一律返回 400/404，不允许继续执行。
- 会话创建、消息计数和最后活动时间由后端维护，不能依赖浏览器时间戳。
- Soul 启用时不存在版本或版本不属于 Agent 返回 404；并发启用在事务中保证只有一个 ACTIVE 版本。
- 旧的 `sess-*` 会话继续可读取；新建会话必须为 UUID。

## 验收标准

1. 新建会话返回标准 UUID，刷新页面后能在同一 Agent 下找到。
2. 打开历史会话能显示历史用户与助手消息、记忆摘要，并可在原 UUID 继续发送。
3. Agent 配置可选择两个已配置模型，Soul 的已启用版本进入 Agent 的系统提示词。
4. Case 和 Feedback 的来源可跳转到原会话与原始消息。
5. 旧数据和既有 Auto/ReAct、Skill/MCP 发布流程保持兼容。
