# Agent 自动长期记忆设计

## 目标与边界

长期记忆服务于业务 Agent 的跨会话学习，不保存用户画像、回答风格或主题信息。记忆空间仅以 `agentId` 隔离。Case 仍由开发人员审核和处理；长期记忆由 Agent 自动新增、更新和软删除，但每次变化必须有可回溯的原始证据与审计记录。

长期记忆只保存数日后仍可帮助 Agent 正确处理业务的问题、规则和经验；实时库存、一次查询结果、整段工具输出、临时报错与未经确认的推测不进入长期记忆。

## 记忆分层

| 层级 | 当前存储 | 职责 |
|---|---|---|
| 原始会话 | `t_agent_message`、工具调用记录 | 保存完整对话、工具参数和工具结果，可按 ID 回源 |
| 短期会话记忆 | 会话摘要、折叠标记与结构化状态 | 为当前会话提供目标、待办、近期上下文和已压缩工具结果 |
| 长期业务记忆 | `agent_memory_card` 与变更审计 | 保存当前 Agent 跨会话可复用的业务知识 |

短期摘要不写入长期记忆；未确认的 Feedback、候选 Case 和运行异常不直接成为长期记忆。已解决 Case 可以作为长期记忆的证据来源，但不是唯一来源。

## 长期记忆模型

一条长期记忆的稳定逻辑身份为：

```text
agentId + memoryType + memoryKey
```

其中 `memoryKey` 是稳定业务名，例如 `inventory.stock-deduction-rule`。当前有效卡片保留稳定 `memoryId`；发生更新时版本号递增，旧版本不参与召回，但保留变更审计。

| 字段 | 作用 |
|---|---|
| `memoryId` | 稳定主键，供召回与正文延迟加载 |
| `agentId` | 业务 Agent 隔离键 |
| `memoryType` | 业务知识类型 |
| `memoryKey` | 同一业务知识的稳定键 |
| `title`、`description` | 轻量索引；`description` 用于粗召回与精排 |
| `content` | Markdown/结构化正文，仅在选中后注入模型 |
| `version` | 当前正文版本 |
| `isDeleted` | 软删除标记；不物理删除记录 |
| `importance`、`pinned` | 重要度与首轮固定注入标志 |
| `sourceType`、`sourceId` | 原始消息、工具调用、Case、Skill 等来源 |
| `updatedReason` | CREATE、UPDATE、RETIRE 的业务原因 |
| `createdAt`、`updatedAt` | 生命周期与排序依据 |

类型限定为：

- `BUSINESS_RULE`：库存阈值、业务口径、Case 升级条件。
- `PROJECT_CONTEXT`：业务背景、稳定架构约束与已确认决策。
- `REFERENCE`：MCP、数据表、接口、Skill 或文档的位置和用途。
- `RESOLVED_CASE`：已解决问题及可靠的处理经验。
- `CAPABILITY_BOUNDARY`：Agent 必须调用 MCP、仅记录 Feedback、禁止臆测等能力边界。

## 自动写入与更新状态机

长期记忆存在两条写入路径：主 Agent 主动保存和回答后的异步增量提取。两条路径最终都调用同一个服务端状态机，模型不能执行 SQL。

```text
稳定业务信息 / 已解决 Case / 绑定 Skill 规则
    ↓
模型生成结构化操作：CREATE / UPDATE / RETIRE / NOOP
    ↓
服务端校验 agentId、memoryKey、来源记录和证据原文
    ↓
写当前记忆卡片与变更审计
    ↓
写索引 Outbox
    ↓
异步同步 pgvector；失败不影响主对话
```

主 Agent 仅可调用：

- `upsert_agent_memory`：按稳定逻辑身份创建或更新当前卡片。
- `retire_agent_memory`：软删除当前卡片。
- `get_agent_memory`：按 ID 读取已发布且未删除正文。

每次回答结束后，异步提取器取得 `sessionId` Redis 锁，读取上次游标之后的新消息与当前 Agent 轻量记忆索引，使用模型判断操作。成功写入后才推进游标；失败保留游标并记录失败原因，后续可重试。若主 Agent 已主动写入同一逻辑记忆，异步提取器识别该操作并避免重复写入。

自动 `RETIRE` 仅允许在以下情况下发生：

1. 新证据明确推翻旧业务规则。
2. 已确认的新规则与旧规则冲突，并指向相同 `memoryKey`。
3. 已解决 Case 重新打开或处理结论被后续事实否定。
4. 被记录的 MCP、Skill、接口或资料已失效。

“当前问题不相关”只能返回 `NOOP`，不能删除长期记忆。

## 三级召回

召回一次只生成一个请求级工作集，整个 ReAct/Auto 工具循环只注入一次，避免重复叠加上下文。

```text
当前问题
  ↓
固定注入：pinned 业务边界和关键规则
  ↓
MySQL 关键词 Top 20 与 pgvector 语义 Top 20 并行粗召回
  ↓
按 memoryId 合并、去重、过滤软删除/过期/跨 Agent 数据
  ↓
轻量模型精排：从候选中返回最多 5 个 memoryId
  ↓
后端白名单校验候选 memoryId
  ↓
延迟加载完整正文并注入本次上下文
```

精排输入只包含当前问题、已执行工具摘要、绑定 Skill 摘要，以及候选的 `memoryId`、类型、标题、描述、重要度和更新时间；不提供完整正文。模型只能返回候选集合中存在的 `memoryId`。精排或向量检索失败时降级为 MySQL 关键词结果或空列表，主对话不得失败。

## 一致性与审计

- MySQL 的当前有效卡片是真实来源；pgvector 只是可重建索引。
- 每次 CREATE、UPDATE、RETIRE 写入审计记录、原文证据和 Outbox。
- UPDATE 使用同一 `memoryId` 与递增 `version`；旧版本索引写 DELETE，新版本写 UPSERT。
- RETIRE 设置软删除/退役状态并写 DELETE Outbox，不物理删除卡片和审计。
- 索引 Worker 消费 UPSERT 前回查当前版本仍有效，防止旧事件在删除后复活向量。
- 全部读取路径必须过滤 `agentId`、软删除和有效期。

## 对现有实现的改造范围

1. 将 `PENDING_REVIEW → APPROVED → PUBLISHED` 长期记忆候选流程替换为自动 `CREATE / UPDATE / RETIRE / NOOP` 操作审计流程；Case 工作流不改变。
2. 增加 `is_deleted`、`importance`、`pinned`、`updated_reason` 与长期记忆变更审计表；保留已有 `memoryId + version`。
3. 新增主 Agent 主动记忆工具，并在异步提取器中检测本轮已执行的主动操作。
4. 将当前搜索改造成 MySQL/pgvector 并行粗召回、精排与正文延迟加载；模型输出候选 `memoryId`，不使用名称。
5. 增加自动更新、软删除、精排白名单、索引删除、重复提取和失败重试测试。

## 验收标准

1. 用户明确给出长期业务规则时，Agent 可调用受控工具更新对应业务记忆。
2. 同一 `agentId + memoryType + memoryKey` 不产生冲突重复卡片；更新后 `memoryId` 稳定、`version` 递增。
3. 自动提取可对新增、更新、软删除和无操作做出区分，且失败不推进游标。
4. 删除后的记忆不能被 MySQL、pgvector 或正文接口召回，但审计和证据仍可查询。
5. 当前请求最多注入一次、最多五条相关记忆；召回失败不影响对话。
6. Case 的人工审核与处理流程不被长期记忆自动化改造破坏。
