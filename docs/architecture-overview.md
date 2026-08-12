# AI Agent Station — 架构设计概览

## 一、项目定位

**AI Agent Station** 是一个轻量级的 AI Agent 配置与管理平台。核心能力：

> **让用户像搭积木一样创建专属 AI Agent**——定义人格(灵魂)、绑定工具(Skills/MCP)、选择模式(Auto/ReAct),然后直接对话。

它不是 Dify 那种重平台,而是一个**学习与实验性质的项目**,聚焦在 Agent 架构的几个核心理念:
- **多 Agent 管理**:每个 Agent 独立配置、独立对话
- **多执行模式**:Auto(多步分析-执行-监督) / ReAct(推理+工具循环)
- **渐进式披露(Progressive Disclosure)**:Skills 和 MCP 按需加载,不浪费上下文
- **配置驱动**:Agent 的灵魂、模型、工具绑定全在 DB,动态装配

---

## 二、架构总览

```
┌─────────────────────────────────────────────────────────┐
│                     Frontend (index.html)               │
│  ┌──────┐  ┌──────────┐  ┌──────┐  ┌────────┐         │
│  │ Chat  │  │  Agents  │  │ MCP  │  │ Skills │         │
│  │ 对话  │  │  管理    │  │ 管理  │  │  管理  │         │
│  └──┬───┘  └──────────┘  └──────┘  └────────┘         │
│     │                                                    │
└─────┼────────────────────────────────────────────────────┘
      │ SSE 流式
      ▼
┌─────────────────────────────────────────────────────────┐
│               Trigger 层 (Controller)                    │
│  AiAgentController   AgentController                    │
│  /api/v1/agent/      /api/v1/agents/                    │
│  auto_agent           /mcp-tools/ /skills/              │
└─────┬────────────────────┬──────────────────────────────┘
      │ 策略路由            │ CRUD
      ▼                    ▼
┌─────────────────────────────────────────────────────────┐
│            Domain 层 (核心逻辑)                          │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ IExecute    │  │ IAgent       │  │ Tool 工具集    │  │
│  │ Strategy    │  │ Repository   │  │               │  │
│  ├─────────────┤  ├──────────────┤  ├───────────────┤  │
│  │ Auto        │  │ queryAgent   │  │ FileReadTool  │  │
│  │ 多步链路    │  │   ById       │  │ FileWriteTool │  │
│  │             │  │ queryBound   │  │ BashTool      │  │
│  │ ReAct       │  │   Skills     │  │ SkillExecute  │  │
│  │ 工具循环    │  │ queryBound   │  │   Tool        │  │
│  │             │  │   Mcps       │  │               │  │
│  │ Intent      │  │ bindSkills   │  │ McpCallTool   │  │
│  │ 分流(Step0) │  │ bindMcps     │  │ (渐进式调用)  │  │
│  └─────────────┘  └──────────────┘  └───────────────┘  │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ 状态机      │  │ Skills       │  │ MCP 客户端    │  │
│  │ AutoAgent   │  │ 扫描器       │  │ 动态注册      │  │
│  │ StateEnum   │  │ (文件系统)   │  │ (AiClient     │  │
│  │ (显式转移)  │  │              │  │  ToolMcpNode) │  │
│  └─────────────┘  └──────────────┘  └───────────────┘  │
└─────┬────────────────────┬──────────────────────────────┘
      │ DAO 接口            │ 合约
      ▼                    ▼
┌─────────────────────────────────────────────────────────┐
│  Infrastructure 层 (数据持久化)                           │
│  AgentRepository  (implements IAgentRepository)          │
│  IAiAgentDao / IAiClientToolMcpDao / ...                │
│  MySQL: ai_agent / ai_client_tool_mcp / ai_agent_skill  │
│         ai_agent_mcp / ai_agent_flow_config / ...       │
└─────────────────────────────────────────────────────────┘

Skills 文件系统           Docker
skills/{id}/SKILL.md     pgvector (RAG)
```

---

## 三、核心执行模式

### 模式 1: Auto（多步链路 + 意图分流 + 状态机）

```
用户输入
  ↓
[Step0: 意图识别]
  ├─ SIMPLE(你好/谢谢) → 快速通道(client 3105) → 直接回复
  │                                              → Summary → Done
  └─ COMPLEX(复杂任务) → [状态机驱动循环]
                          ↓
                  ANALYZE(Step1 分析)
                      ↓
                  EXECUTE(Step2 执行)
                      ↓
                  SUPERVISE(Step3 监督)
                  ├─ PASS → Summary(Step4) → Done
                  ├─ FAIL → ANALYZE(重试)
                  └─ 超步 → Summary(Step4) → Done
```

状态机编码在 `AutoAgentStateEnum`,转移逻辑集中在 `next(ctx)`,不再是硬编码的 get()。

### 模式 2: ReAct（推理 + 工具调用循环 + 渐进式披露）

```
用户输入
  ↓
[ReAct 策略]
  ↓ 读 Agent 配置
  灵魂(soul) = agent.systemPrompt
  模型       = agent.modelId(默认 2001)
  工作目录   = agent.workDir
  绑定 Skills = ai_agent_skill
  绑定 MCP    = ai_agent_mcp
  ↓
[构建系统提示词]
  灵魂 + 工具说明 + Skills 列表 + MCP 列表(仅名+描述)
  ↓
[Spring AI 工具循环(.call() 内部)]
  模型推理 → 决定调工具 → 工具执行 → 观察 → 继续推理 → ... → 最终回答
  ↓
[内部工具]
  read_file / write_file / run_bash
  execute_skill    ← Skills 渐进式披露(返回 SKILL.md 全文)
  call_mcp_tool    ← MCP 渐进式披露(按需调用,不挂全量 schema)
```

---

## 四、数据模型

```
ai_agent ─────────────── ai_agent_skill ─────────── skills/ 目录
  ├ agent_id (pk)          ├ agent_id               ├ {skillId}/SKILL.md
  ├ agent_name             └ skill_id                └ (frontmatter + body)
  ├ description
  ├ system_prompt (灵魂)    ai_agent_mcp ─────────── ai_client_tool_mcp
  ├ model_id                 ├ agent_id                ├ mcp_id
  ├ work_dir                 └ mcp_id                  ├ transport_type
  ├ channel(auto/react)                                ├ transport_config(JSON)
  └ status                                             └ status(启用/禁用)
                              ┌──────────────────┐
                              │ ai_agent_flow_    │
                              │ config            │
     ai_client(3101-3106)     │ (Auto 模式编排)   │
     ai_client_model          └──────────────────┘
     ai_client_api
```

---

## 五、前端布局(终版)

```
┌──────────┬────────────────────────────────────────────────┐
│ 左侧导航  │                主内容区                        │
│           │                                                │
│ 🤖       │  [Agents 页面]                                 │
│  Agents  │  ┌──────┐ ┌──────┐ ┌──────┐                  │
│           │  │🧠 ⚙️ │ │🤖 ⚙️ │ │🧠 ⚙️ │                  │
│ 🔧       │  │Agent1│ │Agent2│ │Agent3│                  │
│  MCP     │  │React │ │Auto │ │  ..  │                  │
│           │  │📚1🔧0│ │📚0🔧0│ │  ..  │                  │
│ 📚       │  └──────┘ └──────┘ └──────┘                  │
│  Skills  │                                                │
│           │  [对话页面 — 点卡片进入]                       │
│           │  ← Agent1 · ReAct · 会话ID: sess-xxx          │
│           │  ┌──────────────────────────┐                 │
│           │  │ 你好                     │  ← 用户         │
│           │  │ 你好！我是文件助手...    │  ← Agent        │
│           │  └──────────────────────────┘                 │
│           │  [输入框...]                                  │
└──────────┴────────────────────────────────────────────────┘
```

---

## 六、渐进式披露(Progressive Disclosure)设计

| 组件 | 第 1 层(提示词) | 第 2 层(工具调用) | 第 3 层(执行) |
|---|---|---|---|
| **Skills** | `execute_skill(skillId)` 工具 + skill 列表(名+描述) | execute_skill 返回 SKILL.md 全文 + 附件清单 | LLM 按手册步骤执行，需附件时 read_file |
| **MCP** | `call_mcp_tool(mcpId, toolName, args)` 工具 + MCP 列表(名+描述) | call_mcp_tool 执行实际 MCP 调用并返回结果 | MCP 服务端执行 |
| **文件** | read_file/write_file/run_bash 直接可用 | - | - |

**避免的做法:** 把 MCP 的全量参数 schema 通过 SyncMcpToolCallbackProvider 挂到 ChatClient 上——这会膨胀上下文,且 LLM 未必需要所有工具的细节。

---

## 七、已经实现的能力清单

- [x] Auto 多步分析-执行-监督链路
- [x] 意图分流(简单问候秒回/复杂任务走多步)
- [x] 显式状态机(AutoAgentStateEnum)
- [x] ReAct 工具循环(读取文件/写入文件/bash)
- [x] Skills 文件系统(SKILL.md + frontmatter 解析)
- [x] MCP 工具管理(CRUD + 动态注册)
- [x] MCP 渐进式调用(call_mcp_tool)
- [x] Agent 专属灵魂(soul / systemPrompt)
- [x] Agent 绑定 Skills/MCP(全量覆盖)
- [x] 模式自动选择(按 channel auto/react)
- [x] 前端侧边栏 + 卡片布局
- [x] pgvector RAG 向量检索
# Agent 长期记忆治理

长期记忆采用“治理数据与检索索引分离”的结构：

- MySQL 是权威数据源，保存候选记忆、证据、审核状态、正式记忆卡片、抽取游标和索引 Outbox。
- PostgreSQL/pgvector 只保存可重建的语义索引与定位字段，不能作为审核事实源。
- 会话原文、滚动摘要和工具折叠属于短期记忆，不会自动发布为跨会话长期记忆。
- 普通会话只能生成待审核候选；只有人工确认的业务规则、操作手册、能力边界，以及来源 Case 已解决的经验才可发布。
- 所有抽取、审核、索引、搜索和正文读取均按 `agent_id` 隔离。

运行时采用渐进式召回：模型先调用 `search_agent_memory` 获取最多 5 条轻量索引，再按需调用
`get_agent_memory` 获取最多 3 条正文。正文读取会回查 MySQL 的 `PUBLISHED` 状态，因此过期、退役、跨 Agent
或仅存在于向量库的记录不会进入模型上下文。
