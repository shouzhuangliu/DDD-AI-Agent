# MCP 渐进式 Schema 披露设计

## 目标

在现有 Agent 的 MCP 统一调用入口上增加“先选工具、再读取完整 Schema、最后调用工具”的渐进式披露流程，避免把多个 MCP 的完整参数定义一次性放入模型上下文，同时保证模型只能调用当前 Agent 已绑定且已确认 Schema 的工具。

## 背景与现状

当前 `ReActExecuteStrategy` 会在系统提示词中注入已绑定 MCP 的工具名、描述和必填参数摘要，并向模型暴露统一的 `call_mcp_tool(mcpId, toolName, args)`。`McpCallTool` 在真正执行前会通过运行中的 `McpSyncClient` 调用 `tools/list`，校验 MCP 绑定、工具名称和必填参数。

现有流程缺少一个显式的“模型获取完整工具 Schema”步骤，因此模型对枚举、数值范围、可选字段和嵌套对象只能依赖摘要或猜测。此次改动只补齐这条链路，不改变 MCP 传输层，也不把所有 MCP 工具动态注册为模型原生 Tool。

## 设计方案

### 1. 两阶段工具流程

```text
模型看到 MCP 摘要
  ↓
模型选择 get_today_feedback
  ↓
get_mcp_tool_schema(mcpId, toolName)
  ↓
返回完整 inputSchema/outputSchema
  ↓
模型按 Schema 生成参数
  ↓
call_mcp_tool(mcpId, toolName, args)
  ↓
MCP tools/call
```

### 2. Schema 查询 Tool

新增受 Agent 工具白名单控制的内部 Tool：

```java
String getMcpToolSchema(String mcpId, String toolName)
```

约束：

- `mcpId` 必须属于当前会话 Agent 的绑定列表；
- `toolName` 必须来自运行时 MCP `tools/list`；
- 仅返回工具名称、标题、描述、`inputSchema`、`outputSchema` 和 Schema 版本摘要；
- 不执行 MCP 业务工具，不读取项目目录，不修改外部数据；
- 查询结果按 `sessionId + mcpId + toolName` 缓存，工具列表变化或 MCP Client 重建时失效。

### 3. 调用门禁

`call_mcp_tool` 在跨进程执行前检查本次会话是否已经成功获取该工具 Schema：

- 未获取：不发送 `tools/call`，返回 `MCP_SCHEMA_REQUIRED`，提示模型先调用 `get_mcp_tool_schema`；
- 已获取：再次确认工具仍属于 Agent 绑定 MCP，并按已获取的 Schema 校验参数；
- MCP 连接、工具不存在、Schema 无法解析等错误只记录为工具运行观察，不升级为业务 Case 证据。

### 4. 提示词与模型上下文

系统提示词只保留 MCP 名称、工具名称、短描述和必填参数摘要，并明确执行规则：

1. 摘要只用于选择候选工具；
2. 选中工具后必须先获取完整 Schema；
3. 获取 Schema 前禁止调用业务 MCP 工具；
4. Schema 查询结果只用于当前会话工具调用，不代表扩大 Agent 权限；
5. 用户只是反馈问题时不自动触发 MCP 查询，用户明确查询反馈时才进入该流程。

### 5. 运行观察与持久化

沿用现有 SSE 和工具消息记录，增加以下事件类型：

- `mcp_schema_requested`：模型请求读取工具 Schema；
- `mcp_schema_loaded`：后端成功返回 Schema；
- `mcp_schema_rejected`：MCP 未绑定、工具不存在或 Schema 无法读取；
- `mcp_call_blocked`：未完成 Schema 获取时拦截真实调用。

事件记录 `sessionId`、`agentId`、`mcpId`、`toolName`、耗时、状态和错误码；Schema 内容本身只在工具消息中按需记录，避免重复写入完整上下文。

## 错误与安全边界

- 模型提供的 `mcpId`、`toolName`、参数均视为不可信输入；
- MCP 绑定校验必须先于 Schema 查询和工具调用；
- Schema 校验失败不自动修改参数，不允许无限重试；
- 工具调用超时、连接失败、返回 `isError` 只作为工具失败，不作为 Feedback 或 Case 证据；
- 认证信息、命令行参数、环境变量和 MCP endpoint 不注入模型上下文；
- Schema 只描述调用契约，不能授予 Agent 未绑定的能力。

## 兼容性范围

- 保留 `call_mcp_tool` 原有调用入口；
- 保留 stdio、SSE、Streamable HTTP 三种现有客户端装配方式；
- 不改变 `tools/list` 和 `tools/call` 的 MCP JSON-RPC 报文；
- 不新增数据库表，Schema 缓存使用会话级运行时状态；
- 不改动前端配置页，只通过现有日志时间线展示新增观察事件。

## 测试验收标准

1. 已绑定 MCP 能读取指定工具的完整 Schema；
2. 未绑定 MCP 无法读取 Schema；
3. 未先读取 Schema 时，真实 MCP 调用被 `MCP_SCHEMA_REQUIRED` 拦截；
4. 读取 Schema 后可以调用对应 MCP 工具；
5. 未知工具、必填参数缺失、Schema 解析失败均不会跨进程执行；
6. 同一会话重复读取相同工具 Schema 使用缓存；
7. 不同 Agent 之间的 Schema 状态和 MCP 权限隔离；
8. 现有 ReAct、MCP、记忆和反馈测试保持通过。

## 非目标

- 本次不将每个 MCP 工具动态转换为模型原生独立函数；
- 本次不实现 MCP Server 注册中心或 HTTP 自动探测重构；
- 本次不改变 Feedback-to-Case 的业务评测规则；
- 本次不把完整 Schema 永久写入长期记忆。
