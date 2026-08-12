package cn.bugstack.ai.domain.agent.service.memory;

/** 当前 Agent 的长期业务记忆写入边界。 */
public interface AgentMemoryLifecyclePort {
    Result upsert(UpsertCommand command);

    Result retire(RetireCommand command);

    record UpsertCommand(String agentId, String memoryType, String memoryKey, String title, String description,
                         String content, int importance, boolean pinned, String sourceType, String sourceId,
                         String evidenceQuote, String reason) { }

    record RetireCommand(String agentId, String memoryId, String sourceType, String sourceId,
                         String evidenceQuote, String reason) { }

    record Result(String memoryId, int version, String operation) { }
}
