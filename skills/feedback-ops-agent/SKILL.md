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
5. 回复必须区分：已确认事实、待确认信息、当前阶段、下一步和人工确认项。

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
