# 动态记忆运行时执行记录

本轮计划已按 Superpowers 的“计划—测试—实现—验证”流程完成，保留原始设计稿作为方案依据。

## 已完成

- [x] 按模型上下文窗口计算有效输入预算：扣除最大输出和安全边界，再按 60%/85% 计算软摘要与硬折叠阈值。
- [x] Chat 和 ReAct 在每次模型调用前重新折叠上下文；ReAct 每轮都从 Map 重新组装 Spring AI 消息，避免重复追加当前用户消息。
- [x] 持久化 assistant `tool_calls_json` 与 tool 结果，使用同一 `tool_call_id` 形成可审计配对。
- [x] 自动挂载只读 `retrieve_tool_call`，并增加分页读取接口，限制单页大小且校验会话隔离。
- [x] 将短期摘要的模型调用移出数据库事务；提交阶段按最新消息 ID 和摘要版本做乐观校验，过期结果丢弃并由下一次后台任务重算。
- [x] 更新开发配置和记忆设计文档，移除固定 8000/16000 阈值作为唯一触发条件。

## 验证结果

定向测试共 7 项通过：

- `ContextBudgetPolicyTest`：3 项
- `ReActRoundFoldingTest`：1 项
- `RetrieveToolCallPaginationTest`：1 项
- `ShortTermMemoryTransactionTest`：1 项
- `ToolCallPersistenceContractTest`：1 项

最终执行：

```powershell
mvn -pl ai-agent-station-study-app -am clean test -DskipTests=false
```

验收重点：原始 MySQL 消息不被折叠改写；模型输入按模型窗口比例控制；旧工具结果可通过 `tool_call_id` 恢复；摘要并发写入不会覆盖新消息；MCP/工具失败只作为运行结果，不作为业务 Case 证据。
