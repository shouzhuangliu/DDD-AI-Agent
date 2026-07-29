# 反馈运维 MCP

启动：

```powershell
python .\mcp-test-server\feedback_ops_mcp.py
```

MCP 配置：

```json
{
  "command": "python",
  "args": ["D:/javacode/ai-agent/ai-agent-station-study/mcp-test-server/feedback_ops_mcp.py"],
  "env": {"FEEDBACK_MCP_DATA_DIR": "D:/javacode/ai-agent/ai-agent-station-study/mcp-test-server/data"}
}
```

工具分层：

- 采集：`create_feedback`、`search_feedback`、`get_feedback_detail`
- 升级：`promote_feedback_to_case`
- 证据：`append_case_evidence`、`get_case_timeline`
- 运维只读：`search_incidents`、`get_service_health`

它不会执行发布、重启、删除或修改生产配置。写入工具只写本地演示数据，接入真实数据库时应由后端 Service 替换实现。
