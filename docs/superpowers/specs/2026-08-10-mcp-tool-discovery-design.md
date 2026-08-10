# MCP 工具按需检索与句柄调用设计

## 背景

当前 Agent 会话会把绑定 MCP 的工具名称和部分参数摘要拼入系统提示词，然后由模型传入 `mcpId`、`toolName` 再读取完整 Schema。绑定 MCP 或工具数量变多后，提示词会膨胀，模型还需要自行判断同名工具属于哪个 MCP。

## 目标

将运行时流程调整为：Agent 只注入绑定 MCP 的服务摘要；模型通过 `discover_mcp_tools` 按用户意图检索工具；服务端在当前 Agent 绑定范围内返回最多 3 个候选工具的完整 `inputSchema`；每个候选生成会话级 `toolHandle`；模型使用 Handle 调用真实 MCP。

## 非目标

- 本次不新增 MCP 数据库表，不改变 MCP Server 的 JSON-RPC 协议。
- 本次不删除旧的 `get_mcp_tool_schema(mcpId, toolName)` 和 `call_mcp_tool(mcpId, toolName, args)`，保留兼容已有会话和历史 Feedback 记录。
- 本次不引入向量数据库检索；候选排序使用确定性的名称、描述、Token 词项和业务别名匹配。

## 运行时设计

### 1. Agent 提示词

只注入 MCP 服务级信息：

```text
- inventory-feedback-mcp：库存反馈查询与分诊服务
  可按需检索其业务工具
```

不再将所有 MCP 工具名称和完整 Schema 注入提示词。提示词只声明两个平台工具：

```text
discover_mcp_tools(query, mcpId?, limit?)
call_mcp_tool(toolHandle, args)
```

### 2. 工具发现

模型调用：

```json
{
  "query": "查询今日库存反馈",
  "mcpId": "inventory-feedback-mcp",
  "limit": 3
}
```

服务端必须：

1. 读取当前 `ReActToolContext` 的 Agent 和绑定 MCP 清单。
2. 若传入 `mcpId`，校验该 MCP 已绑定；未传入时只在所有已绑定 MCP 中检索。
3. 对每个 MCP 调用 `tools/list`，读取工具名称、描述和 `inputSchema`。
4. 对查询文本进行确定性归一化，按工具名、描述和预置业务别名计算匹配分。
5. 按分数降序、`mcpId + toolName` 升序稳定排序，最多返回 3 个候选。
6. 为每个候选生成随机 `toolHandle`，把 Handle、Agent、会话、MCP、工具名、Schema 哈希和过期时间保存到 `ReActToolContext`。
7. 返回候选工具的 `toolHandle`、`mcpId`、`toolName`、描述和完整 `inputSchema`。

无候选时返回明确的 `MCP_TOOL_NOT_FOUND`，禁止模型猜测工具名。

### 3. Handle 调用

新增优先调用入口：

```json
{
  "toolHandle": "mcp-tool-...",
  "args": {
    "limit": 20,
    "source": "all"
  }
}
```

服务端通过当前会话 Handle 解析唯一的 MCP 版本和工具，并校验：

- Handle 属于当前会话和 Agent。
- Handle 未过期。
- MCP 仍绑定到当前 Agent。
- Schema 哈希和当前缓存一致。
- 参数符合 Schema 的必填字段和 JSON 类型约束。

校验通过后创建 `McpSchema.CallToolRequest`，继续使用现有 `McpSyncClient.callTool` 执行 MCP。旧参数形式继续走原有调用路径。

## 错误处理

- MCP 未绑定：`MCP_NOT_BOUND`。
- `tools/list` 失败：`MCP_TOOL_DISCOVERY_FAILED`，不执行任何业务工具。
- 没有候选：`MCP_TOOL_NOT_FOUND`。
- Handle 不属于当前会话：`MCP_TOOL_HANDLE_REJECTED`。
- Handle 过期或 Schema 变化：`MCP_TOOL_HANDLE_EXPIRED`。
- 参数不符合 Schema：返回参数校验错误，不发送 `tools/call`。

所有发现和调用结果继续通过现有工具观察日志、LLM 调用日志和工具调用持久化链路记录。

## 兼容性

`get_mcp_tool_schema(mcpId, toolName)` 与旧 `call_mcp_tool(mcpId, toolName, args)` 保留，作为历史会话和人工调试入口。新 ReAct 提示词只引导模型使用 `discover_mcp_tools` 和 Handle 调用，避免新旧路径混用。

## 验收标准

1. 绑定一个 MCP 时，模型只看到 MCP 服务摘要，不看到全部工具列表。
2. `discover_mcp_tools` 只检索当前 Agent 绑定的 MCP，最多返回 3 个候选。
3. 两个 MCP 暴露同名工具时，返回的 Handle 仍能唯一定位到正确 MCP。
4. 使用其他会话或过期 Handle 调用时，MCP Server 不会收到 `tools/call`。
5. 参数不符合返回 Schema 时，MCP Server 不会收到 `tools/call`。
6. 旧 Schema 读取、旧调用和 Feedback 入库测试全部保持通过。
