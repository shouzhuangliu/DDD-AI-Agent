# 动态记忆运行时设计

## 背景

记忆折叠不能依赖一个对所有模型都相同的字符阈值。模型窗口、输出长度、系统提示词和工具描述都会
变化；同时 ReAct 每一轮都会新增 assistant 工具调用和 tool 响应，入口折叠一次无法控制后续请求。

## 设计

1. `ContextBudgetPolicy` 按模型 profile 计算有效输入 token：
   `contextWindow - maxOutput - safetyMargin`。模型 profile 可通过 `agent.memory.context.models`
   配置，缺失时回退到默认 32K；摘要和强折叠分别使用有效输入的 60%/85%。
2. Chat 与 ReAct 共用 `MemoryFoldingPipeline`。ReAct 内部维护规范化消息 Map，每次调用模型前
   将 Map 映射为 Spring AI Message，确保工具历史在下一轮再次受预算控制。
3. ReAct 返回 assistant tool call 后立即持久化 `tool_calls_json`，工具返回后持久化 tool 行；
   `retrieve_tool_call` 仅按当前 session 和 ID 查询原文，分页接口最多单页 20000 字符。
4. 短期摘要读取快照、调用模型、提交结果分离。写入事务校验最新消息 ID 和旧摘要版本，发生竞争
   时放弃旧结果；摘要失败和工具失败均不作为业务 Case 证据。

## 验证重点

- 32K 与未知模型的比例预算测试。
- ReAct 工具结果分页和调用 ID 映射测试。
- assistant/tool 持久化映射契约测试。
- 摘要刷新方法无事务，摘要持久化方法有短事务的反射契约测试。
