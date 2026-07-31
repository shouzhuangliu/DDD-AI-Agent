# Inventory Feedback Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个可本地运行的库存 Feedback MCP 与渐进式库存业务 Skill 包，支撑 Agent 先拉取今日反馈再评估紧急 Case。

**Architecture:** 使用一个 dependency-free 的 Python stdio MCP 作为本地反馈数据源；使用一个入口 Skill 和五个 reference 文档承载库存业务判断规则；配套测试、README 和推荐系统提示词，让这套原型可直接被 Agent 使用。

**Tech Stack:** Python 3、JSON-RPC over stdio、Markdown Skills、unittest、Vue/Java Agent 现有能力装配

## Global Constraints

- 不接真实数据库，测试数据直接内置到 MCP 脚本
- Skill 必须按业务阶段拆分为多个 md，支持渐进式加载
- MCP 必须支持本地 `stdio` 启动
- 文案统一使用中文

---

### Task 1: 新增库存业务 Skill 包

**Files:**
- Create: `skills/inventory-feedback-agent/SKILL.md`
- Create: `skills/inventory-feedback-agent/skill.json`
- Create: `skills/inventory-feedback-agent/references/01-feedback-intake.md`
- Create: `skills/inventory-feedback-agent/references/02-inventory-classification.md`
- Create: `skills/inventory-feedback-agent/references/03-urgency-evaluation.md`
- Create: `skills/inventory-feedback-agent/references/04-case-promotion.md`
- Create: `skills/inventory-feedback-agent/references/05-daily-summary.md`

**Interfaces:**
- Consumes: MCP 工具 `get_today_feedback`、`get_feedback_detail`
- Produces: 面向 Agent 的业务判断规则与渐进式路由说明

- [ ] 定义 Skill 入口和 references 目录
- [ ] 编写库存反馈 intake 规则
- [ ] 编写库存问题分类规则
- [ ] 编写紧急度评测规则
- [ ] 编写 Case 升级标准
- [ ] 编写日报输出模板

### Task 2: 新增库存 Feedback MCP

**Files:**
- Create: `mcp-test-server/inventory_feedback_mcp.py`
- Create: `mcp-test-server/INVENTORY_FEEDBACK_MCP.md`
- Create: `mcp-test-server/data/inventory_triage.jsonl`

**Interfaces:**
- Produces:
  - `get_today_feedback(arguments: object) -> list`
  - `get_feedback_detail(arguments: object) -> object`
  - `list_inventory_services(arguments: object) -> list`
  - `search_feedback_by_keyword(arguments: object) -> list`
  - `mark_feedback_triaged(arguments: object) -> object`

- [ ] 写 MCP 工具清单
- [ ] 写内置测试反馈数据
- [ ] 实现 JSON-RPC initialize / ping / tools/list / tools/call
- [ ] 实现工具调用逻辑
- [ ] 写启动说明与配置示例

### Task 3: 为 MCP 补测试

**Files:**
- Create: `mcp-test-server/tests/test_inventory_feedback_mcp.py`

**Interfaces:**
- Consumes: `inventory_feedback_mcp.py` 中的工具函数
- Produces: 可验证今日反馈拉取、详情查询、关键词搜索、分诊标记的测试

- [ ] 先写失败测试
- [ ] 运行测试确认失败
- [ ] 写最小实现
- [ ] 运行测试确认通过

### Task 4: 补 Agent 接入说明

**Files:**
- Create: `docs/inventory-feedback-agent-quickstart.md`

**Interfaces:**
- Produces: Agent 配置字段、MCP 配置 JSON、推荐系统提示词、演示问法

- [ ] 写 stdio 配置示例
- [ ] 写推荐 systemPrompt
- [ ] 写演示问题样例
- [ ] 写“后续如何替换为真实数据库”说明
