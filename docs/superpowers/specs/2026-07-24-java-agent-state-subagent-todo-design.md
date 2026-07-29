# Java Agent State、ReAct、Subagent 与 Todo 设计

## 1. 文档目的

本文把 `angx-main` 的 State、ReAct 循环、Subagent 并行执行和 Todo 设计，映射到当前 Java 项目。目标是形成一套可以逐步落地的设计，避免继续增加互相平行的执行架构。

本轮只确认设计，不修改 Java 实现。

## 2. 已确认的现状

### 2.1 Angx 的关键做法

Angx 的 `ThreadState` 是一个线程/会话级状态容器，包含沙箱、工作目录、Agent 名称、产物、Todo 和上传文件等信息。状态随 checkpointer 持久化，middleware 通过状态读写能力协作。

Angx 的执行链不是一个简单的无限 `while`，而是：

```text
读取 State
  -> before middleware
  -> 调用模型
  -> after middleware
  -> 执行工具
  -> 更新 State
  -> 检查循环、Todo、摘要和限制
  -> 继续下一轮或生成最终回答
```

Angx 的 Subagent 有两个层次的线程池：调度线程负责提交任务和等待，执行线程负责真正运行子 Agent。任务使用独立任务 ID 和状态对象记录 `PENDING/RUNNING/COMPLETED/FAILED/TIMED_OUT/CANCELLED`，主 Agent 通过任务工具等待结果，并把结果作为工具结果交回主上下文。

Angx 的 Subagent 限制 middleware 会在模型已经生成多个 `task` 调用后再次截断超出的调用，不能只依赖提示词约束。Todo 只在 Plan 模式启用，Todo 是 State 的字段，并且在消息被摘要后会主动注入当前 Todo 提醒，防止模型丢失计划上下文。

### 2.2 当前 Java 的关键做法

当前 Java 已有以下能力，应继续复用：

| 现有能力 | 当前位置 | 设计定位 |
| --- | --- | --- |
| 路由 | `AiAgentController`、`ChatAgentRoutePolicy` | 决定 Plan/ReAct/Chat/Auto 模式 |
| ReAct 工具执行 | `ReActExecuteStrategy`、`AbstractReActTool` | 工具白名单、MCP、文件、命令和记忆工具 |
| 工具请求上下文 | `ReActToolContext`、`ReActToolContextHolder` | 当前请求的会话、沙箱、绑定 Skill/MCP、SSE |
| Auto 状态树 | `AutoAgentExecuteStrategy`、`DynamicContext` | 现有树式状态机，可作为状态迁移参考 |
| 会话消息 | `ChatMessageRecorderDb`、`chat_message` | 用户、助手、工具消息和对话历史 |
| 记忆 | `MemoryFoldingPipeline`、`memory_summary`、`memory_state` | 历史折叠和长期记忆，不替代执行状态 |
| SSE | `ResponseBodyEmitter` | 对话正文、工具 action/observation、完成和错误事件 |
| 模型 | `ModelSelectionService`、`ChatModelBeanRegistrar` | 当前请求选择数据库模型 Bean |

当前 ReAct 仍主要依赖 Spring AI `ChatClient` 内部工具循环。`ExecuteCommandEntity.maxStep=30` 已经传入路由，但还没有真正约束 Spring AI 内部循环。因此，后续实现必须把一次模型调用和一次工具批次拆出来，由项目持有循环计数。

## 3. 目标与非目标

### 3.1 目标

1. 每次 Agent 请求都有唯一的、可恢复的 `AgentExecutionState`。
2. Plan 模式最多执行 5 个计划循环；一次循环允许最多 4 个独立 Subagent 任务并行。
3. ReAct 模式最多执行 30 个模型/工具步，并且到达上限后必定停止继续调用工具。
4. Todo 可以通过 SSE 展示，并在执行状态中持久化。
5. Subagent 有独立上下文、超时、取消、失败和结果汇总状态。
6. 继续使用 `chat_message` 保存用户可见聊天记录，执行状态和工具过程不混入普通聊天正文。
7. Skill 继续通过沙箱里的 `read_file` 渐进式加载，MCP 继续通过绑定的 `call_mcp_tool` 调用。

### 3.2 非目标

1. 本方案不引入 LangGraph，也不迁移 Python 运行时。
2. 本方案不替换现有模型 Bean 注册、模型选择和 Agent 配置。
3. 本方案不让 Controller 直接写 SQL；查询和事务由 DAO/Service 负责。
4. 本方案不把 Skill 做成 `execute_skill` 工具。
5. 本方案不把每个工具中间结果都当作用户对话消息展示。

## 4. Java 状态模型

建议在 domain 层新增 `AgentExecutionState`，作为一次用户消息执行的根状态。它和 `sessionId` 是不同层次：一个会话可以有多次执行，一个执行也可能有多个 Subagent。

```text
AgentExecutionState
├── executionId
├── sessionId
├── agentId
├── modelId
├── routeType: PLAN | REACT | CHAT | AUTO
├── status: RUNNING | COMPLETED | FAILED | CANCELLED | TIMED_OUT
├── cycle: 当前 Plan 循环
├── step: 当前模型/工具步
├── maxCycles: 5
├── maxSteps: 30
├── todos: TodoItem[]
├── toolHistory: ToolCallState[]
├── subagents: SubagentTaskState[]
├── currentTask
├── lastAssistantContent
├── errorMessage
├── startedAt
└── completedAt
```

状态修改必须经过 `AgentExecutionStateService`，不能由 Controller、工具类和模型回调分别修改同一份计数。所有状态更新使用 `executionId` 做幂等键。

### 4.1 TodoItem

```text
TodoItem
├── todoId
├── content
├── status: PENDING | IN_PROGRESS | COMPLETED | BLOCKED | CANCELLED
├── owner: LEAD | SUBAGENT
├── subagentTaskId
├── position
├── createdAt
└── updatedAt
```

约束：普通 ReAct 不自动创建 Todo；Plan 模式才启用 Todo。默认只有一个 `IN_PROGRESS` 项，只有明确并行任务时允许多个。Todo 每次变更立即保存并推送 `todo_updated` 事件，不能等整个请求结束后批量保存。

### 4.2 工具步与循环定义

一个 ReAct step 定义为一次 lead model 请求以及该请求返回的一批工具调用和工具结果。不能按单个 SSE token 计步，也不能把一次工具内部的文件读取递归当成多步。

```text
step += 1
调用模型一次
如果没有 tool call -> 生成最终回答并结束
如果有 tool call -> 执行工具批次 -> 保存结果 -> 进入下一 step
```

当 `step == 30`：不再执行模型返回的新增工具调用；将已收集结果交给一次“仅文本收尾”阶段，或直接使用最近助手内容结束。实现时必须在执行工具前检查预算，防止超限工具已经开始执行。

一个 Plan cycle 定义为：Lead Agent 读取当前 Todo、完成一次拆解/决策、最多提交 4 个相互独立的 Subagent 任务、等待可汇总结果、更新 Todo。Plan 最多 5 个 cycle。4 个 Subagent 是一个 cycle 的并发上限，不是总任务数；超过 4 个任务必须进入下一批或下一 cycle。

## 5. ReAct 执行设计

### 5.1 推荐调用链

```text
AiAgentController
  -> ChatAgentRoutePolicy
  -> AgentExecutionService.start
  -> ReActExecutionEngine.run
       -> StateService.loadOrCreate
       -> ModelSelectionService.select
       -> build prompt and tool callbacks
       -> model call (one call only)
       -> StateService.incrementStep
       -> tool batch executor
       -> SSE action/observation
       -> loop detector and budget guard
       -> final answer
       -> ChatMessageRecorder.recordAssistant
```

`ReActExecuteStrategy` 可以保留为路由入口，但真正的循环应下沉到 `ReActExecutionEngine`。每次模型调用前重建或复用同一个状态上下文，每次工具批次结束后保存状态。不要继续把整个 `client.prompt().call()` 作为不可分割的 30 步执行。

### 5.2 循环检测

参考 Angx，使用稳定哈希记录最近 20 个工具调用批次，哈希只包含工具名和规范化参数。

```text
同一批次重复第 3 次 -> 推送 loop_warning，要求模型收尾
同一批次重复第 5 次 -> 清空下一次 tool calls，强制文本收尾
```

循环检测状态必须按 `executionId` 隔离，不能使用全局静态 Map。执行结束、取消或超时必须清理。

### 5.3 模型输出安全处理

模型返回 tool call 后，只有经过预算检查、绑定能力检查和参数解析的调用才进入工具执行器。循环检测或预算拦截时，不能留下没有对应 ToolMessage 的悬空 tool call，否则 OpenAI 兼容接口会返回 400。

## 6. Plan 与 Todo 设计

Plan 使用现有路由和 `DynamicContext` 的状态机思想，但不把所有状态继续塞在 Map 中。`DynamicContext` 后续可以适配或包装 `AgentExecutionState`，逐步迁移以下状态：

```text
INTENT -> PLAN_READY -> SUBAGENTS_RUNNING -> SYNTHESIZING
       -> TODO_UPDATED -> COMPLETED
       -> BLOCKED / FAILED / CANCELLED
```

5 个 cycle 是硬编码的产品规则，代码中使用常量 `PLAN_MAX_CYCLES = 5`。每个 cycle 内最多 4 个并行 Subagent；Subagent 本身不再创建 Subagent，避免递归膨胀。

Plan 的模型提示只负责提出计划和任务，服务端负责：

1. 校验 Todo 格式和状态迁移。
2. 截断超过 4 个的并行任务。
3. 保存每个任务状态。
4. 等待任务终态后再允许汇总。
5. 在第 5 个 cycle 后禁止新任务，只允许收尾。

## 7. Subagent 双线程与并发

### 7.1 Java 映射

新增 `SubagentExecutionService`，使用应用级 `ThreadPoolTaskExecutor`，分成两个职责：

```text
subagent-scheduler: 接收、登记、超时监视、发布状态
subagent-executor: 运行实际 ChatClient/工具循环
```

这对应 Angx 的 scheduler pool 和 execution pool。调度线程不得直接执行模型调用，执行线程不得负责 HTTP/SSE 长轮询。

### 7.2 状态机

```text
PENDING -> RUNNING -> COMPLETED
                   -> FAILED
                   -> TIMED_OUT
                   -> CANCELLED
```

每个任务必须带：`taskId`、`executionId`、`parentAgentId`、`traceId`、`taskDescription`、`status`、`result`、`error`、`startedAt`、`completedAt`、`attempt`。任务结果只能由服务端写入一次终态，重复回调必须幂等。

### 7.3 独立上下文

Subagent 可以继承父执行的 `agentId`、工作目录、沙箱根目录、绑定 Skill/MCP 和模型 ID，但使用自己的消息上下文和 `taskId`。它不能直接写父会话的 assistant 消息；它只写自己的执行日志，最后由 Lead Agent 汇总成一条用户可见回答。

工具上下文不能继续只依赖 `ThreadLocal` 传递 Subagent 关系。保留 `ReActToolContext` 兼容现有工具，同时增加不可变的 `ExecutionContext` 参数或请求级上下文，至少包含 `executionId` 和 `taskId`。

### 7.4 并发限制

服务端限制每个 Plan cycle 最多 4 个 Subagent，每个 Agent 执行最多 4 个并发任务。这个限制由服务端强制执行，模型提示只作为引导。队列满时任务进入 `PENDING`，不能静默丢弃。

## 8. SSE 事件契约

沿用现有 `ResponseBodyEmitter`，统一事件 envelope：

```json
{
  "type": "todo_updated",
  "executionId": "...",
  "sessionId": "...",
  "step": 2,
  "cycle": 1,
  "data": {}
}
```

至少支持：

| 事件 | 用途 |
| --- | --- |
| `execution_started` | 返回 executionId 和模式 |
| `state_updated` | 返回步数、循环和状态 |
| `todo_updated` | 返回 Todo 当前快照 |
| `tool_action` | 工具开始调用 |
| `tool_observation` | 工具返回结果 |
| `subagent_started` | 子任务进入运行 |
| `subagent_completed` | 子任务完成并带摘要结果 |
| `subagent_failed` | 子任务失败/超时/取消 |
| `assistant_final` | 用户可见最终回答 |
| `execution_completed` | 执行终态 |
| `execution_error` | 可恢复或不可恢复错误 |

事件发送失败不能回滚数据库状态；SSE 断开后执行仍按取消策略处理，至少要把状态标记为可查询的终态。后续可以按 executionId 查询状态和结果。

## 9. 持久化设计

### 9.1 复用现有表

继续使用：

- `ai_session`：会话标题、预览、最后消息时间、当前模型。
- `chat_message`：user/assistant/tool 的用户可见和审计消息。
- `ai_llm_log`：每次模型调用的性能和错误日志。
- `memory_summary`、`memory_state`、`memory_tool_result`：记忆和工具结果折叠。

不把 Todo、Subagent 状态拼到 `chat_message.content`，也不把执行计数放进长期记忆。

### 9.2 建议新增表

建议新增 migration，由 infrastructure DAO 访问：

```text
agent_execution
├── id
├── execution_id UNIQUE
├── session_id
├── agent_id
├── model_id
├── route_type
├── status
├── current_cycle
├── current_step
├── max_cycles
├── max_steps
├── state_json
├── error_message
├── started_at
├── completed_at
└── updated_at

agent_execution_todo
├── id
├── execution_id
├── todo_id
├── content
├── status
├── owner
├── subagent_task_id
├── position
└── timestamps

agent_subagent_task
├── id
├── task_id UNIQUE
├── execution_id
├── parent_task_id NULL
├── task_description
├── status
├── result_text
├── error_message
├── trace_id
├── started_at
├── completed_at
└── updated_at
```

第一版可以将结构化状态放在 `agent_execution.state_json`，但 Todo 和 Subagent 仍建议单独表，以支持日志页按执行、任务和状态查询。Controller 只调用 `ExecutionQueryService`，不直接执行 SQL。

## 10. 取消、超时、失败和恢复

1. 用户取消时，根状态先变为 `CANCELLED`，随后设置所有未终态 Subagent 的取消标记。
2. 工具和模型调用都必须有超时；超时后不能继续等待 HTTP 请求。
3. 一个 Subagent 失败不应自动导致其它独立任务失败；汇总阶段明确标记部分失败。
4. 根执行失败时保存 `errorMessage` 和最后状态，并发送 `execution_error`。
5. 应用重启后，扫描 `RUNNING/PENDING` 执行，将租约过期的任务标记为 `TIMED_OUT` 或重新排队；不能依赖 JVM 静态 Map 恢复。
6. 所有状态转移使用当前状态条件更新，避免超时线程覆盖已经完成的结果。

## 11. 与 Skill、MCP、记忆和模型的兼容

### Skill

继续沿用当前工作目录同步和 `read_file` 渐进式加载：系统提示只列出绑定 Skill 的名称、描述和沙箱相对路径，模型需要时读取 `SKILL.md`，再读取其相对资源。Subagent 继承父 Agent 的绑定 Skill，但不能读取未绑定 Skill。

### MCP

继续使用当前绑定 MCP ID 和 `call_mcp_tool`。State 只保存本次执行已调用的 MCP 工具摘要和结果引用，不把完整 schema 复制进每轮消息。MCP 调用仍需由 allowlist 和绑定关系双重校验。

### 记忆

每次执行开始读取 `ChatMessageRecorder` 的历史和记忆折叠结果；执行状态中的 Todo、工具调用和子任务状态不写入长期记忆，除非最终 assistant 回答明确总结后由现有记忆流程处理。

### 模型

对话途中选择的 `modelId` 固定到本次 `agent_execution`，Subagent 默认继承父模型。切换模型只影响新的执行，不改变正在运行的执行，也不需要恢复旧的固定 `client-ids` 自动装配。

## 12. 分阶段实施

### Phase 1：状态与边界

新增 domain 状态对象、状态枚举、状态服务接口和数据库 DAO；补 execution migration；不改变现有用户路径，只在 ReAct/Plan 入口创建执行记录。

### Phase 2：ReAct 真实循环

把一次 `ChatClient.call()` 拆成单模型步；加入 30 步预算、工具批次执行、循环检测、终态保存和 SSE `state_updated`。增加预算和悬空 tool call 测试。

### Phase 3：Todo/Plan

加入 Plan cycle 状态机和 Todo DAO；Plan 使用 5 个 cycle 硬限制，每次变更实时推送 SSE；加入摘要后 Todo 仍可恢复的测试。

### Phase 4：Subagent

加入 scheduler/executor 两个线程池、任务状态 DAO、4 个并发限制、取消/超时和结果汇总；Subagent 先只允许一层，禁止递归创建。

### Phase 5：观测与恢复

补执行详情查询、任务列表、重启恢复、幂等更新、指标和日志字段，并与现有 Dify 风格日志页面对接。

## 13. 测试与验收标准

### 单元测试

- 状态步数不能超过 30。
- Plan cycle 不能超过 5。
- 单 cycle 超过 4 个 Subagent 时被服务端截断或排队。
- Todo 状态迁移非法时被拒绝。
- 相同工具调用达到阈值后会强制收尾。
- 取消、超时和重复终态更新保持幂等。

### 集成测试

- 一次 ReAct 请求能写入 execution、chat_message 和 ai_llm_log。
- 工具 action/observation 与最终 assistant 消息顺序正确。
- 4 个独立 Subagent 能并行运行并被 Lead 汇总。
- 一个 Subagent 失败不会丢失其它成功结果。
- SSE 断开后数据库状态仍可查询。
- 应用重启后不会留下永久 RUNNING 任务。

### 验收标准

1. 日志中能按 `executionId/sessionId/agentId` 还原一次执行。
2. 前端能看到 Plan Todo、ReAct 步数和 Subagent 状态。
3. 达到 30 步或 5 个 Plan cycle 后，服务端不会再发起新的工具调用。
4. 当前 Skill、MCP、模型选择和数据库会话历史行为不回归。
5. Controller 不包含业务 SQL，所有持久化通过 Service/DAO 完成。

## 14. 需要确认的实现决策

以下决策已按当前需求给出默认值，实施前不再重新发明规则：

| 项目 | 默认值 |
| --- | --- |
| Plan 最大循环 | 5 |
| 每个 Plan cycle 最大并行 Subagent | 4 |
| ReAct 最大 step | 30 |
| Subagent 最大嵌套层级 | 1（禁止 Subagent 创建 Subagent） |
| 循环检测警告阈值 | 相同工具批次 3 次 |
| 循环检测强制停止阈值 | 相同工具批次 5 次 |
| 循环检测窗口 | 最近 20 个工具批次 |
| Subagent 默认模型 | 继承父执行模型 |

