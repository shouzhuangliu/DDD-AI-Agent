# 库存 Feedback Agent 设计说明

## 目标

构建一个可本地部署的库存业务 Agent 原型，让大模型能够：

1. 通过 MCP 拉取“今日 Feedback”
2. 基于库存业务 Skills 逐步判断问题类型与紧急程度
3. 输出可升级为 Case 的候选项

## 设计范围

本次只实现演示级闭环，不接真实数据库，不接真实外部系统。

- Feedback 来源：本地 Python MCP 内置测试数据
- Skills 组织：一个入口 `SKILL.md` + 多个分业务阶段的 reference 文档
- MCP 传输：优先支持 `stdio`
- 评测方式：由大模型结合 Skill 文档进行业务判断

## Skill 结构

新增 `skills/inventory-feedback-agent/`：

- `SKILL.md`：只负责入口、状态路由、调用边界
- `references/01-feedback-intake.md`：拉取今日反馈
- `references/02-inventory-classification.md`：库存问题分类
- `references/03-urgency-evaluation.md`：紧急程度判断
- `references/04-case-promotion.md`：Case 升级标准
- `references/05-daily-summary.md`：日报输出模板

这样可以支持渐进式加载，避免一次把所有业务规则塞进上下文。

## MCP 结构

新增 `mcp-test-server/inventory_feedback_mcp.py`，提供：

- `get_today_feedback`
- `get_feedback_detail`
- `list_inventory_services`
- `search_feedback_by_keyword`
- `mark_feedback_triaged`

数据源使用脚本内置列表，同时允许把处理记录写入本地 `jsonl` 文件，便于演示“已分诊”状态变化。

## 成功标准

1. 你可以本地运行 Python MCP
2. 你的 Agent 可以配置并调用该 MCP
3. 模型问“帮我爬取今日的 Feedback 看看有没有紧急 case”时，能够先调 MCP，再结合技能文档输出判断结果
4. 项目内包含 README / MCP 配置示例 / 建议系统提示词，便于直接接入
