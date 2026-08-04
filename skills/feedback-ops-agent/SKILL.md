---
name: feedback-ops-agent
description: 收集企业用户、运维和监控反馈，经过分类、证据收集和人工审核后升级为 Case。
---

# 反馈运维闭环

这是一个渐进式 Skill。当前文件只定义入口和路由，不承载所有业务细节。

## 目录

```text
feedback-ops-agent/
├── SKILL.md
├── skill.json
└── references/
    ├── 01-intake.md
    ├── 02-classification.md
    ├── 03-incident-triage.md
    ├── 04-case-evidence.md
    ├── 05-case-review.md
    ├── 06-case-resolution.md
    └── 07-agent-profile.md
```

## 使用规则

1. 首次处理反馈时，读取 `references/01-intake.md`。
2. 每次只读取当前阶段对应的一个 reference，不要一次性读取全部文件。
3. 当前阶段完成后，根据 reference 文件末尾的“下一步”决定下一次读取哪个文件。
4. 需要工具时，优先使用本 Agent 绑定的 `feedback-ops-mcp` 工具。
5. 回复必须区分：已确认事实、待确认信息、当前阶段和下一步；只有用户明确要求“升级/发布/确认 Case”时，才列出人工确认项。

## 自动分诊规则

- 用户说“查询/查看今日反馈”时，先调用已绑定 MCP 做只读查询，再直接返回事实汇总；不要询问是否授权、不要把查询结果交给用户确认。
- 用户说“结合业务 Skill 分诊/评测/巡检”时，先读取当前 Agent 绑定的业务 Skill，再调用对应 MCP 获取证据，逐条输出业务分类、优先级、证据充分性、缺失信息和“候选 Case/暂不升级”结论。
- 自动评测可以写入 `AI_EVALUATING`、`VALID`、`NEED_MORE_INFO` 等分诊状态，但不得自动发布或确认正式 Case；人工确认只发生在 Case 发布边界。
- 查询、分诊、评测过程中禁止读取项目代码、运行 Bash 或调用未绑定工具；除非用户明确要求“排查代码/运行命令”。

## 状态路由

```text
新反馈 -> 01-intake
已记录 -> 02-classification
P0/P1 或运维事件 -> 03-incident-triage
普通问题或证据不足 -> 04-case-evidence
证据齐全 -> 05-case-review
人工确认 Case -> 06-case-resolution
Case 已解决 -> 07-agent-profile
```

## 权限边界

- `promote_feedback_to_case` 只能创建 `PENDING_REVIEW`，不能代替管理人员确认。
- MCP 不允许发布、重启、删除或修改生产配置。
- 长期画像只能写入当前 Agent 的命名空间，不能跨 Agent 使用。
- 不读取或记录密码、API Key、Cookie 和完整个人隐私信息。
