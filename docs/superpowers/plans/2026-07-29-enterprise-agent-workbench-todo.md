# 企业级 Agent 工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前 AI Agent 工作台从“可演示雏形”推进到“能体现真实企业反馈治理场景”的可交付项目。

**Architecture:** 以后端业务闭环为主线，先收口 Feedback → Case → 处理追踪，再把 Agent 装配能力、日志可观测性、长期记忆和前端工作台逐层补齐。优先保证“数据准、状态清、来源可追踪”，避免继续堆前端表象。

**Tech Stack:** Spring Boot 3、MyBatis、Spring AI、MySQL、PostgreSQL/pgvector、Vue 3、Vite

## Global Constraints

- 不覆盖用户本地未提交修改，尤其是 `ai-agent-station-study-app/src/main/java/cn/bugstack/ai/Application.java`
- 所有状态流转必须以后端规则为准，前端只做展示和触发
- 自动采集的数据必须坚持“少而准”，不能让噪声污染仪表盘
- 未勾选的 Skill / MCP / Tool 不允许注入给 Agent
- 日志、反馈、Case、记忆都必须按 `agentId + sessionId` 可追踪
- 所有中文网站文案必须中文化，避免英文状态直接暴露给用户
- 每个阶段都要附带最小回归测试，避免“功能看起来有了，链路其实没通”

---

### Task 1: 重写并固化企业级实施 todo

**Files:**
- Create: `docs/superpowers/plans/2026-07-29-enterprise-agent-workbench-todo.md`
- Modify: `docs/superpowers/plans/2026-07-29-feedback-case-phase1.md`

**Interfaces:**
- Consumes: 当前项目代码结构、已有设计文档、近期用户确认的产品方向
- Produces: 后续开发统一执行清单、优先级排序、阶段性交付目标

- [x] 梳理当前已完成能力与未完成能力边界
- [x] 按后端优先原则拆成业务闭环、装配治理、日志可观测、记忆体系、前端打磨五大阶段
- [x] 写出本计划文档作为后续统一 todo

### Task 2: 收口 Feedback → Case 的后端业务闭环

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAutoCaptureService.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAdmissionPolicy.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/analysis/FeedbackEvaluationWorker.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentOperationsController.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/operations/WorkflowTransitionPolicy.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/analysis/FeedbackEvaluationWorkerTest.java`
- Test: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/feedback/FeedbackAdmissionPolicyTest.java`

**Interfaces:**
- Consumes: 用户消息、反馈入队、评测状态、人工操作状态
- Produces: 清晰的 `OPEN / NEED_MORE_INFO / VALID / PROMOTED / RESOLVED / INVALID` 反馈流转与 `CANDIDATE / PENDING_REVIEW / CONFIRMED / IN_PROGRESS / RESOLVED` Case 流转

- [x] 修复反馈准入规则，拦截 `1/hi/test` 等噪声
- [x] 修复反馈评测 worker 的中文命中与分类逻辑
- [ ] 增加“晋升为 Case”的后端阈值规则，避免单次模糊反馈直接升级
- [ ] 打通人工审核动作与状态说明，返回给前端清晰中文状态
- [ ] 为 Feedback→Case 晋升补最小回归测试
- [x] 跑通当前后端准入/评测回归测试

### Task 3: 做实 Agent 的 Skills / MCP / Tool 装配治理

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/tools/core/ReActToolProperties.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/capability/CapabilityRegistryService.java`
- Modify: Agent 配置相关 Trigger/Infrastructure DAO 与前后端接口文件（待按当前目录精确落位）

**Interfaces:**
- Consumes: Agent 勾选的 Tool / Skill / MCP 列表
- Produces: 运行时可见能力清单、提示词注入元信息、严格的未授权不可见约束

- [ ] 梳理当前 Agent 配置表、接口、运行时注入路径
- [ ] 修复“勾选了 Skill 但运行时没装配”的后端逻辑
- [ ] 修复“取消 Bash 仍能看到 Bash”这类工具白名单失效问题
- [ ] 给 Agent 注入 skill 虚拟路径与 metadata，而不是让模型盲猜
- [ ] 增加最小回归测试：未勾选不可见、已勾选可调用

### Task 4: 收口 Agent / 会话维度日志与可观测性

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/conversation/ConversationSessionService.java`
- Modify: 日志聚合/查询相关 Trigger Service、DAO、VO 文件（待按当前目录精确落位）
- Modify: `frontend-vue/src/App.vue`

**Interfaces:**
- Consumes: LLM 调用记录、工具调用记录、会话消息、反馈与 Case 关联关系
- Produces: “按 Agent 分组、按会话展开”的完整时间线视图

- [ ] 把日志数据结构统一到 `agentId + sessionId + messageId`
- [ ] 支持同一会话下查看用户消息、模型回复、工具调用、思考/路由轨迹
- [ ] 把仪表盘上的统计切换为真实后端口径，不混入 AI signal 噪声
- [ ] 修复日志页滚动/展开/列表过长展示问题

### Task 5: 接入长期记忆并与反馈治理联动

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/service/memory/*`
- Modify: `ai-agent-station-study-trigger/src/test/java/cn/bugstack/ai/trigger/service/memory/*`
- Modify: 记忆召回入口与仪表盘摘要接口

**Interfaces:**
- Consumes: 会话摘要、已发布 Case、已确认反馈、Agent 画像
- Produces: 可召回长期记忆摘要、重复问题识别、Case/Feedback 辅助判断依据

- [ ] 用 pgvector + embedding 模型把长期记忆真正入库
- [ ] 定义长期记忆写入来源：反馈、Case、会话摘要
- [ ] 定义召回规则：按 agentId 限定、按业务关键词召回
- [ ] 在仪表盘展示“可回召的长期记忆摘要”

### Task 6: 打磨前端工作台，严格映射后端能力

**Files:**
- Modify: `frontend-vue/src/App.vue`
- Modify: `frontend-vue/src/styles.css`

**Interfaces:**
- Consumes: 后端 Agent、Feedback、Case、日志、记忆、装配能力接口
- Produces: 中文化、可操作、边界清晰的企业工作台界面

- [ ] 所有英文状态替换为中文业务语义
- [ ] Agent 配置页补齐 Tool / Skill / MCP 勾选器
- [ ] Feedback、Case、日志、会话拆成明确工作台入口
- [ ] 对话记录支持删除、重命名、继续会话
- [ ] Case 与 Feedback 展示来源与晋升关系

### Task 7: 阶段回归验证与规范提交

**Files:**
- Modify: 本计划文档
- Modify: 相关实现文件

**Interfaces:**
- Consumes: 本阶段代码变更
- Produces: 可验证结果、规范提交记录、远端同步

- [ ] 每一阶段至少跑对应模块最小回归测试
- [ ] 需要前端变更时执行 `npm run build`
- [ ] 使用规范中文提交信息提交
- [ ] 推送到 `origin/main`

## 当前执行顺序

1. Feedback → Case 后端闭环
2. Agent 装配治理
3. Agent/会话日志与仪表盘口径
4. 长期记忆联动
5. 前端总打磨

## 当前就绪结论

- Feedback 准入与评测第一轮已落地并已回归验证
- 下一步直接进入“晋升为 Case 的后端阈值与审核流收口”
- 这一步完成后，仪表盘数据才会真正稳定、可信
