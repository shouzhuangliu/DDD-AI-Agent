package cn.bugstack.ai.domain.agent.service.memory;

import java.util.List;

/** Agent-scoped, governed long-term memory catalog exposed to runtime tools. */
public interface AgentMemoryCatalogPort {

    List<MemoryIndexItem> search(String agentId, String query, int limit);

    List<MemoryContent> getPublished(String agentId, List<String> memoryIds);

    record MemoryIndexItem(String agentId, String memoryId, int version, String memoryType,
                           String title, String description, String sourceCaseId, double score) { }

    record MemoryContent(String agentId, String memoryId, int version, String memoryType,
                         String title, String description, String contentJson, String sourceCaseId) { }
}
