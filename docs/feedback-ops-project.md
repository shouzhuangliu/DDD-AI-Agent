# 企业反馈运维闭环项目

## 目标

统一接收用户反馈、运维告警和内部问题，经过分类、证据收集、人工审核后进入现有 Case 工作台。已解决 Case 再按 Agent 隔离沉淀为长期画像。

## 组件

- `feedback-ops-agent`：只负责反馈闭环，不绑定固定模型；对话中可以选择当前模型。
- `skills/feedback-ops-*`：每个目录一个阶段，入口 Skill 只负责路由，避免一次性加载全部业务规则。
- `mcp-test-server/feedback_ops_mcp.py`：本地可运行的 STDIO MCP，提供反馈、Case 证据和运维只读查询。
- `V20260729__seed_feedback_ops_agent.sql`：创建 Agent、Skills 绑定、MCP 版本和本地发布绑定。
- 现有 Case 工作台：负责状态、审核、负责人、回退和归档，不由 MCP 绕过。

## 渐进式读取

```text
feedback-ops-agent
  -> feedback-ops-intake
  -> feedback-ops-classification
  -> feedback-ops-incident-triage 或 feedback-ops-case-evidence
  -> feedback-ops-case-review
  -> feedback-ops-case-resolution
  -> feedback-ops-agent-profile
```

每轮只读取当前阶段文档。Agent 必须先保存事实，再作分类；需要升级 Case 时只创建 `PENDING_REVIEW`，由管理人员在 Case 工作台确认。

## 本地启动

```powershell
python .\mcp-test-server\feedback_ops_mcp.py
```

数据库迁移后，进入 Agent 编辑页检查 `反馈运维助手`，应看到 8 个已绑定 Skill 和 `反馈运维 MCP`。MCP 的 STDIO 配置使用相对路径 `mcp-test-server/feedback_ops_mcp.py`，启动 Java 应用时工作目录应为项目根目录；部署时把该目录随应用发布，或通过数据库更新 endpoint 配置。

## 安全边界

MCP 不提供重启、发布、删除、改生产配置等工具。写入类工具只保存反馈和证据，Case 升级必须由 Agent 明确说明人工确认结果；真实环境应将 Python 的 JSONL 存储替换为后端 Service 和数据库事务。
