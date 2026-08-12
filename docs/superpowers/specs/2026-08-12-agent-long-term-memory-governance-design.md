# Agent 长期记忆治理与渐进式召回设计

## 1. 背景与目标

当前系统已经具备完整会话持久化、异步滚动摘要、上下文折叠、工具结果按
`tool_call_id` 取回、pgvector 召回和已解决 Case 画像等能力，但短期会话摘要与长期知识之间的
边界仍然不够清晰：合格的会话摘要会被直接写入长期向量库，召回服务也会把历史会话摘要与
长期画像混合排序。这会导致普通聊天、未审核判断和时效性工具结果被误当成稳定业务知识。

本设计借鉴 `lord-rings` 项目的三项机制：

1. 使用可管理的结构化记忆卡片作为长期记忆权威数据；
2. 使用增量游标和异步任务抽取记忆，失败时不推进游标；
3. 先召回轻量索引，再按 ID 读取完整内容，控制上下文体积。

结合本项目 Feedback-to-Case 业务，最终目标是：

- 短期记忆只服务当前会话，不自动升级为长期事实；
- 长期记忆按 `agent_id` 隔离，不引入用户和多租户维度；
- 只有人工确认的业务规则和已解决 Case 经验可以发布；
- MySQL 是长期记忆权威数据源，BGE-M3 + pgvector 是可重建的检索索引；
- 每条记忆都能追溯到 Case、会话消息或工具调用原文；
- 向量库不可用时不阻塞对话，并可降级为元数据和关键词检索。

## 2. 不做的事情

- 不把普通会话摘要、未审核 Feedback、候选 Case、模型观察或工具异常直接写入正式长期记忆；
- 不把库存数量、今日反馈数量等实时数据保存为长期事实；
- 不使用 pgvector 作为记忆的唯一数据源；
- 当前阶段不增加用户、租户和 Topic 级隔离；
- 不让模型绕过服务端状态机直接发布或删除正式记忆。

## 3. 记忆分层

### 3.1 原始证据层

继续使用 MySQL 保存完整会话消息、模型调用、工具调用参数与结果。原始记录不可被摘要覆盖，
是 Feedback、Case 和长期记忆的最终证据来源。

### 3.2 会话工作记忆

继续使用现有 `memory_summary`、`memory_state` 和 `memory_tool_result`：

- `memory_summary`：当前会话的版本化滚动摘要；
- `memory_state`：目标、约束、实体、待办和已完成事项；
- `memory_tool_result`：工具结果的结构化结论；
- 完整工具结果仍从原始消息表按 `session_id + tool_call_id` 取回。

会话摘要只能用于当前会话折叠和候选记忆抽取，不进入正式长期记忆召回池。

### 3.3 长期记忆候选

模型可从有效会话或 Case 中生成结构化候选，但候选没有推理注入权限。候选只用于人工审核，
状态为：

```text
EXTRACTED -> PENDING_REVIEW -> APPROVED -> PUBLISHED
                           \-> REJECTED
```

`APPROVED` 表示内容通过人工审核；`PUBLISHED` 表示记忆卡片和向量索引均已建立。普通会话产生的
候选必须人工审核；Case 只有在进入 `RESOLVED` 状态后才允许进入发布流程。

### 3.4 正式长期记忆卡片

正式卡片只保存稳定、可复用、可追溯的内容，按 Agent 隔离。首期类型限定为：

- `BUSINESS_RULE`：业务 Skill 中未覆盖或经人工确认的新规则；
- `RESOLVED_CASE`：已解决 Case 的问题模式、定位结论和处理经验；
- `OPERATING_PLAYBOOK`：人工确认的业务处理步骤；
- `CAPABILITY_BOUNDARY`：该 Agent 可处理和不可处理的业务边界。

不设置通用 `USER` 偏好类型，因为当前产品不按用户隔离。

### 3.5 Agent Profile

保留现有 `agent_memory_profile`，但将其定位为正式记忆卡片的版本化物化视图，而不是另一份独立
事实来源。Profile 由发布后的卡片编译生成，包含业务规则、常见故障模式、解决经验和能力边界；
任意内容都必须能反查 `memory_card_id` 和 `source_case_id`。

## 4. 数据模型

### 4.1 `agent_memory_candidate`

| 字段 | 说明 |
| --- | --- |
| `id` | 候选 ID |
| `candidate_id` | UUID 业务标识 |
| `agent_id` | Agent 隔离键 |
| `memory_type` | 候选记忆类型 |
| `memory_key` | Agent 内稳定业务键，用于去重 |
| `title` | 简短名称 |
| `summary` | 用于审核和检索的摘要 |
| `content_json` | 结构化候选内容 |
| `source_type` | `SESSION`、`CASE` 或 `MANUAL` |
| `source_session_id` | 来源会话，可空 |
| `source_case_id` | 来源 Case，可空 |
| `confidence` | 模型置信度，仅供排序，不能代替审核 |
| `status` | 候选状态 |
| `extraction_model_id` | 抽取模型 |
| `prompt_version` | 抽取提示词版本 |
| `reviewed_by/reviewed_at/review_comment` | 人工审核信息 |
| `created_at/updated_at` | 审计时间 |

### 4.2 `agent_memory_evidence`

一条候选或正式卡片可关联多条证据：

| 字段 | 说明 |
| --- | --- |
| `memory_owner_type` | `CANDIDATE` 或 `CARD` |
| `memory_owner_id` | 候选或卡片 ID |
| `source_type` | `MESSAGE`、`TOOL_CALL`、`FEEDBACK`、`CASE` |
| `source_id` | 原始记录业务 ID |
| `session_id` | 来源会话 |
| `tool_call_id` | 来源工具调用，可空 |
| `evidence_quote` | 有长度限制的原文片段 |
| `content_hash` | 原文哈希，便于发现源数据变化 |

服务端必须校验证据属于同一 `agent_id`，且引用内容确实存在于原始记录中。工具超时、MCP 异常和
模型运行观察不能作为业务事实证据。

### 4.3 `agent_memory_card`

| 字段 | 说明 |
| --- | --- |
| `id` | 数据库主键 |
| `memory_id` | UUID；同一记忆跨版本保持不变 |
| `agent_id` | Agent 隔离键 |
| `memory_type` | 正式记忆类型 |
| `memory_key` | Agent 内稳定业务键 |
| `version` | 版本号，单调递增 |
| `title/description` | 轻量索引信息 |
| `content_json` | 完整结构化内容 |
| `status` | `PUBLISHED`、`SUPERSEDED`、`RETIRED` |
| `source_candidate_id` | 来源候选 |
| `source_case_id` | 来源 Case，可空 |
| `effective_at/expires_at` | 生效与过期时间 |
| `published_by/published_at` | 发布审计 |
| `created_at/updated_at` | 记录时间 |

唯一性由 `(agent_id, memory_key, version)` 保证。发布新版本时，在同一事务中将旧版本置为
`SUPERSEDED`，新版本置为 `PUBLISHED`。

### 4.4 `agent_memory_extraction_cursor`

按 `agent_id + session_id` 保存 `last_message_id`、`version`、`last_status` 和重试信息。只有候选及其
证据成功提交后才推进游标，模型失败、JSON 非法或数据库冲突时保持原游标，等待后台任务重试。

### 4.5 `agent_memory_index_outbox`

MySQL 事务只负责发布卡片和写入索引事件；后台消费者调用 BGE-M3 生成向量并写入 pgvector。
成功后标记 `DONE`，失败按退避策略重试。这样避免 MySQL 已发布但向量写入失败造成不可恢复的
双写不一致。

## 5. 长期记忆写入链路

### 5.1 会话候选抽取

1. 对话完成后复用现有异步分析任务，不阻塞 HTTP/SSE 回复；
2. 以 `agent:memory:extract:{agentId}:{sessionId}` 获取 Redis 短租约；
3. 从抽取游标之后读取新增的有效用户消息、模型答复和必要工具结论；
4. 注入当前 Agent 已绑定 Skill 的规则摘要，要求模型判断内容是否稳定、是否属于当前业务；
5. 模型只返回候选 JSON，不直接操作正式记忆；
6. 服务端校验类型、Agent 归属、证据引用、实时性、重复项和内容长度；
7. 候选与证据在一个事务内保存，然后推进抽取游标；
8. 候选进入 `PENDING_REVIEW`，不会参与模型召回。

候选抽取应设置有效信息门槛。单字回复、寒暄、纯确认、模型运行错误以及没有业务对象或业务影响的
内容不产生候选。

### 5.2 Case 发布链路

```text
Feedback -> AI 评测 -> 候选 Case -> 人工审核 -> 处理中 -> 已解决
                                                     |
                                                     v
                                           长期记忆候选/发布
```

只有 `RESOLVED` Case 可以生成 `RESOLVED_CASE` 卡片。发布内容至少包含：问题模式、适用条件、
最终结论、处理方式、来源 Case 和证据引用。Case 被重新打开或结论被推翻时，相关卡片进入
`RETIRED`，不得继续召回。

### 5.3 人工规则发布

业务负责人可将稳定规则或处理手册直接创建为 `MANUAL` 候选，审核后发布。业务 Skill 仍然是当前
规则的首要依据；长期记忆用于补充经过实际 Case 验证的经验，不能静默覆盖 Skill。

## 6. 渐进式召回链路

### 6.1 查询准入

沿用 `MemoryQueryAdmissionPolicy`，只在当前问题包含业务对象、历史指代、规则判断或处理经验需求时
触发长期记忆。寒暄、单字回复和简单确认不召回。

### 6.2 混合候选检索

1. 强制过滤 `agent_id`、`status=PUBLISHED` 和有效期；
2. 使用 MySQL 的类型、时间和 `memory_key` 做精确过滤；
3. 使用 BGE-M3 + pgvector 做语义召回；
4. 合并关键词与向量结果，按相关度、类型权重、新鲜度和证据质量重排；
5. 最多返回 5 条轻量索引，每条只包含 ID、标题、描述、类型、来源和分数。

pgvector 记录只保存 `memory_id`、`version`、`agent_id`、`memory_type` 等定位元数据。完整内容必须
回到 MySQL 按 ID 获取，避免向量库成为第二份不可治理的数据源。

### 6.3 两阶段注入

第一阶段由 Spring AI Advisor 在模型调用前注入轻量索引：

```text
[可用长期记忆]
- id=mem-101，类型=RESOLVED_CASE，标题=库存已扣减但订单创建失败，来源=case-28
- id=mem-135，类型=BUSINESS_RULE，标题=预占库存释放条件，来源=manual-review-7

仅当这些记忆与当前任务相关时，调用 get_agent_memory 获取正文；不得凭标题补造事实。
```

第二阶段通过受控工具读取正文：

```text
get_agent_memory(memoryIds=["mem-101"])
```

服务端再次校验 `agent_id` 和发布状态，再返回正文、版本、证据来源和时效信息。每轮最多取 3 条，
并受独立 Token 预算限制。模型不需要相关历史时可以完全不读取正文。

## 7. 与现有实现的改造关系

### 保留

- `chat_message` 和 LLM/工具调用持久化；
- `memory_summary`、`memory_state` 的异步滚动摘要；
- Redis 摘要锁、数据库版本校验和覆盖游标；
- `MemoryFoldingPipeline` 与 `retrieve_tool_call`；
- `LongTermMemoryPort` 抽象、BGE-M3 和 pgvector 基础设施；
- 已解决 Case 触发长期画像更新的业务入口。

### 调整

1. 删除 `ShortTermMemoryService` 将 `SESSION_SUMMARY` 写入 `LongTermMemoryPort` 的逻辑；
2. 删除 `LongTermMemoryRecallService` 直接查询 `memory_summary` 并混入长期结果的逻辑；
3. 将 `LongTermMemoryPort` 从“长期事实存储”改为“正式卡片索引端口”；
4. `AgentMemoryProfileService` 不再直接拼接 Case 字段，而是从已发布卡片编译画像；
5. 新增候选、证据、卡片、抽取游标和索引 Outbox；
6. 新增 `search_agent_memory` 与 `get_agent_memory` 的渐进式取回能力；
7. 统一在服务端完成 Agent 绑定和证据校验，模型只能建议，不能越权发布。

## 8. 一致性、并发与失败策略

- Redis 锁只避免同一会话被多个 Worker 同时抽取，不替代数据库事务；
- 抽取游标通过版本或 CAS 更新，旧任务不能覆盖新进度；
- 候选使用 `agent_id + memory_type + memory_key + source_id` 做幂等去重；
- 正式发布使用数据库事务保证版本切换；
- pgvector 写入通过 Outbox 最终一致，失败不会阻塞 Case 关闭和正常对话；
- 召回失败时降级到 MySQL 精确/关键词检索；
- 记忆正文取回失败时只忽略该条记忆，不重复执行历史 MCP 或业务工具；
- 所有模型抽取结果记录模型 ID、提示词版本和来源证据，便于回放与评测。

## 9. 测试与验收

### 单元测试

- 普通摘要不会写入正式长期记忆；
- 单字、寒暄、运行错误不会产生候选；
- 未审核 Feedback 和未解决 Case 无法发布；
- Agent A 无法搜索或读取 Agent B 的记忆；
- 证据原文不匹配时拒绝候选；
- 新版本发布后旧版本变为 `SUPERSEDED`；
- Case 重新打开后相关卡片不可召回；
- pgvector 不可用时能够关键词降级。

### 集成测试

1. 库存 Agent 通过 MCP 获取当日反馈；
2. Skill 判断其属于库存业务并产生候选 Case；
3. 人工审核、处理并关闭 Case；
4. 系统生成长期记忆候选并完成发布；
5. 新会话询问相似问题时先看到轻量索引；
6. 模型按 ID 读取记忆正文并引用来源 Case；
7. 全链路可以从回复追溯到卡片、Case、Feedback 和原始工具调用。

## 10. 推荐实施顺序

1. 先纠正短期摘要与长期召回边界；
2. 增加长期记忆卡片、候选、证据和状态机；
3. 将已解决 Case 接入候选审核和发布流程；
4. 增加 Outbox 与 pgvector 索引同步；
5. 实现轻量索引搜索和按 ID 取回正文；
6. 最后把 Profile 改造成已发布记忆卡片的版本化物化视图。

该顺序优先消除错误记忆污染，再补齐可审核、可追溯、可渐进召回的完整链路。
