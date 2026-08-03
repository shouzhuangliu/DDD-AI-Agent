# Case 证据门禁运行说明

## 数据库迁移

本版本新增 `case_evaluation_snapshot` 评测快照表，并为 `case_evidence` 增加证据角色、Skill 规则和 supports JSON 字段。已有 Docker 数据卷不会自动重放 init 脚本，请在 MySQL 客户端执行：

```sql
SOURCE ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260803__case_evidence_gate.sql;
```

`compose.local.yml` 已将该迁移挂载为 `12-case-evidence-gate.sql`；只有删除并重建本地卷时，Docker 才会自动执行它。

## 评测频率

- 首次 Case 评测：默认积累 2 条有意义的用户/运维业务证据，并等待会话空闲窗口后触发。
- 增量评测：上次评测后新增 2 条业务证据才再次触发；明确负反馈可提前触发。
- `1`、问候、`OK`、`继续`、助手占位文案不会触发大模型评测，也不会生成 Feedback 或 Case。
- 短期记忆：保留最近 24 条消息；至少 4 条新的有意义用户/运维消息且达到 8000 token 才滚动摘要，16000 token 为硬上限。

## Case 生成门槛

模型只能输出结构化评测。服务端还会校验当前 Agent 的 Skill ID、规则 ID、业务对象、实际结果、业务影响，以及用户/运维/MCP 原文引用。证据不足只保存评测快照并进入 `NEED_MORE_INFO`，不会写入 `ai_case`。
