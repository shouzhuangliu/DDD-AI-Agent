# Feedback/Case 主链路第一阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通会话路由、Feedback 收口和 Case 升级入口，让系统先具备可用的业务反馈治理主链路。

**Architecture:** 这一阶段不重写整套平台，而是在现有 Agent、会话、反馈、Case、Vue 页面基础上做“主链路收束”。后端优先修正消息路由与反馈准入，前端优先拆清会话、Feedback、Case 的职责边界，并把英文状态替换为中文业务状态。

**Tech Stack:** Spring Boot 3、MyBatis、Spring AI、Vue 3、Vite、MySQL

## Global Constraints

- 不破坏现有 Agent / MCP / Skills 基础配置能力。
- 低质量消息不能直接进入正式 Feedback 指标。
- 只有明确排查请求才进入工具执行链路。
- 会话、Feedback、Case 必须作为三个不同工作台呈现。
- 本阶段完成后必须跑后端测试和前端构建验证。

---

### Task 1: 收束消息路由与反馈准入

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/route/ChatAgentRoutePolicy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAdmissionPolicy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AiAgentController.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/FeedbackAdmissionPolicyTest.java`

**Interfaces:**
- Consumes: 用户消息、Agent 模式、当前会话上下文
- Produces: `routeType`、是否进入 Feedback 候选、是否进入工具执行

- [ ] 编写失败测试，覆盖“普通咨询 / 反馈意图 / 明确排查 / 噪声输入”四类输入。
- [ ] 运行目标测试，确认在现有实现下至少有一类失败。
- [ ] 修改路由策略：反馈意图优先进入反馈链路，只有明确排查才进入 ReAct。
- [ ] 修改反馈准入策略：`1`、`hi`、纯噪声不进入正式 Feedback。
- [ ] 运行相关测试，确认通过。
- [ ] 提交一次小步提交。

### Task 2: 整理 Feedback 与 Case 的状态入口

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/CaseMemoryPublisher.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/AgentMemoryProfileService.java`
- Test: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/operations/EnterpriseSchemaContractTest.java`

**Interfaces:**
- Consumes: Feedback 状态、审核动作、Case 升级动作
- Produces: 更清晰的 Feedback 状态返回、Case 升级入口、记忆回灌边界

- [ ] 找出现有 Feedback 与 Case 在接口上的混杂点。
- [ ] 调整后端接口返回，区分 AI评测、人工审核、Case 升级三个层次。
- [ ] 保证未确认的候选 Case 不写入长期记忆。
- [ ] 跑契约测试或新增最小回归测试。
- [ ] 提交一次小步提交。

### Task 3: 调整前端工作台边界

**Files:**
- Modify: `frontend-vue/src/App.vue`
- Modify: `frontend-vue/src/styles.css`

**Interfaces:**
- Consumes: Agent、会话、Feedback、Case、日志接口
- Produces: 更清晰的会话/Feedback/Cases 页面结构和中文业务文案

- [ ] 调整导航，确保会话、Feedback、Cases 为独立工作台。
- [ ] 在会话页明确显示路由结果和反馈触发状态。
- [ ] 在 Feedback 页显示 AI评测结果、人工动作和升级入口。
- [ ] 在 Cases 页显示来源反馈、状态流转和处理动作。
- [ ] 将主要英文状态码替换为中文文案。
- [ ] 本地构建 Vue，确认不报错。
- [ ] 提交一次小步提交。

### Task 4: 回归验证与推送

**Files:**
- Modify: `docs/superpowers/specs/2026-07-29-agent-feedback-governance-design.md`
- Modify: `docs/superpowers/plans/2026-07-29-feedback-case-phase1.md`

**Interfaces:**
- Consumes: 前三步改动结果
- Produces: 已验证的阶段性交付与规范提交记录

- [ ] 运行 `mvn -q -DskipTests=false test`。
- [ ] 运行 `npm run build`（`frontend-vue`）。
- [ ] 记录验证结果并补充文档。
- [ ] 使用中文规范提交信息提交。
- [ ] 推送到 `origin/main`。
