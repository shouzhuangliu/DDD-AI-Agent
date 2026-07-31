# 库存 Feedback MCP

这是一个本地演示用 MCP，面向“库存业务 Agent”。

它的目标不是接真实数据库，而是提供一套你本地即可启动、可被大模型真实调用的 Feedback 数据源。

## 启动方式

```powershell
python .\mcp-test-server\inventory_feedback_mcp.py
```

## MCP 配置（stdio）

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

## 工具说明

- `get_today_feedback`：读取今天的库存 Feedback
- `get_feedback_detail`：读取单条反馈详情
- `list_inventory_services`：列出库存相关服务域
- `search_feedback_by_keyword`：按关键词过滤反馈
- `mark_feedback_triaged`：写入一条本地分诊记录

## 建议搭配的 Skill

- `inventory-feedback-agent`

## 推荐问法

- 帮我抓取今日 Feedback 看看有没有紧急 Case
- 先读取今天的库存反馈，再判断哪些需要升级为 Case
- 帮我做一份今日库存巡检摘要
