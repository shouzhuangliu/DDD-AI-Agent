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

## 排查“返回空列表”

本项目同时提供两个本地 STDIO MCP：

- `inventory_feedback_mcp.py`：库存业务演示数据，今日查询工具是 `get_today_feedback`。
- `feedback_ops_mcp.py`：通用反馈流水，`search_feedback` 读取 `data/feedback.jsonl`；该文件不存在时返回空列表是正常结果。

如果用户问“查询今日反馈”，库存 Agent 必须绑定前一个 MCP。日志中应看到
`call_mcp(inventory-feedback-mcp/get_today_feedback)`；若看到
`search_feedback`，说明绑定了错误的本地 MCP，不能把空列表解释成“今日没有反馈”。

stdio MCP 不需要手动常驻运行。应用通过 MCP 配置按需启动 Python 子进程；只有在单独调试协议时，才直接执行本文档开头的命令。
