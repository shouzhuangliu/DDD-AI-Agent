package cn.bugstack.ai.domain.agent.service.memory;

import java.util.List;

/** Reserved boundary. No automatic cross-session implementation is enabled in this release. */
public interface LongTermMemoryPort {
    void store(MemoryFact fact);
    List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit);

    /**
     * Indexes a governed, published memory card. Implementations must propagate failures so the
     * transactional outbox worker can retry instead of silently losing the index update.
     */
    default void index(PublishedMemoryDocument document) { }

    default void delete(String agentId, String memoryId, int version) { }

    record PublishedMemoryDocument(String agentId, String memoryId, int version, String memoryType,
                                   String title, String description, String searchText,
                                   String sourceCaseId, String status) { }

    record MemoryFact(String agentId, String subjectId, String kind, String content,
                      String sourceSessionId, String consentReference,
                      String sourceCaseId, int profileVersion) {
        public MemoryFact(String agentId, String subjectId, String kind, String content,
                          String sourceSessionId, String consentReference) {
            this(agentId, subjectId, kind, content, sourceSessionId, consentReference, "", 0);
        }
    }
}
