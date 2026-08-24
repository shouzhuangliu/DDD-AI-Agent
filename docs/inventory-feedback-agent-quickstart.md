# 库存 Feedback Agent 快速接入

## 1. 启动本地 MCP

```powershell
cd D:\javacode\ai-coding\ai-agent-station-study
python .\mcp-test-server\inventory_feedback_mcp.py
```

> 这是 `stdio` 模式。通常不需要你手动长期挂着，平台在调用时会按 MCP 配置拉起它。

## 2. 在平台里新增 MCP

推荐填写：

- 名称：库存 Feedback MCP
- 传输协议：`stdio`
- 启动配置：

```json
{
  "command": "python",
  "args": [
    "D:/javacode/ai-coding/ai-agent-station-study/mcp-test-server/inventory_feedback_mcp.py"
  ],
  "env": {
    "INVENTORY_FEEDBACK_MCP_DATA_DIR": "D:/javacode/ai-coding/ai-agent-station-study/mcp-test-server/data"
  }
}
```

## 3. 在平台里导入 Skill

使用目录：

```text
skills/inventory-feedback-agent
```

## 4. Agent 推荐配置

- Agent ID：`inventory-agent`
- Agent 名称：`库存巡检智能体`
- Channel：`react`
- 绑定 MCP：`inventory-feedback-mcp`
- 绑定 Skill：`inventory-feedback-agent`

## 5. 推荐系统提示词

```text
你是一个库存业务巡检 Agent。

你的工作方式必须遵循以下顺序：
1. 当用户要求查看今日反馈、巡检反馈、识别紧急 Case 时，先调用 MCP 工具 get_today_feedback。
2. 只对库存业务相关反馈进行判断，分类时遵循 inventory-feedback-agent skill 的 reference 文档。
3. 不要一上来就升级为 Case，必须先给出：问题分类、紧急度、证据、缺失信息。
4. 当证据足够且影响业务时，才建议升级为候选 Case。
5. 输出必须使用中文，结构清晰，优先给出“今日最紧急的库存问题”。

如果用户只是让你看今日反馈，不要调用其他无关工具。
```

## 6. 推荐演示问法

- 帮我抓取今日 Feedback 看看有没有紧急 Case
- 先拉取今天库存相关反馈，再判断哪些需要升级为 Case
- 给我做一份今日库存反馈巡检摘要

## 7. 后续如何替换为真实源

当前脚本里的 `TODAY_FEEDBACK` 是写死测试数据。

后面要接真实系统时，只需要保留 MCP 协议层不动，把以下函数的数据源替换掉即可：

- `get_today_feedback`
- `get_feedback_detail`
- `search_feedback_by_keyword`

你可以替换成：

- MySQL 查询
- HTTP API
- 爬虫抓取结果
- 消息队列消费结果

## 8. 长期记忆验证与召回

1. 库存 Agent 通过已绑定的 Feedback MCP 获取当天反馈，并结合库存业务 Skill 形成 Feedback/候选 Case。
2. 开发人员审核 Case；只有 Case 进入 `RESOLVED` 后，系统才生成 `RESOLVED_CASE` 长期记忆候选。
3. 在长期记忆候选列表中填写审核人和理由，依次批准、发布。发布事务同时写入 MySQL 正式卡片与 Outbox。
4. 索引 Worker 异步将卡片的标题、摘要和定位字段写入 pgvector；失败会重试，不阻塞聊天。
5. 新会话询问历史库存问题时，Agent 先搜索轻量索引，确实需要时再按 `memoryId` 取正式正文。

治理接口前缀：`/api/v1/agents/{agentId}/memory`。主要接口包括候选列表、批准、驳回、发布、退役、
记忆搜索及正文读取。所有写接口都要求非空审核人和理由。
