package cn.bugstack.ai.domain.agent.service.memory;

import java.util.List;

/** Reserved boundary. No automatic cross-session implementation is enabled in this release. */
public interface LongTermMemoryPort {
    void store(MemoryFact fact);
    List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit);

    record MemoryFact(String agentId, String subjectId, String kind, String content,
                      String sourceSessionId, String consentReference,
                      String sourceCaseId, int profileVersion) {
        public MemoryFact(String agentId, String subjectId, String kind, String content,
                          String sourceSessionId, String consentReference) {
            this(agentId, subjectId, kind, content, sourceSessionId, consentReference, "", 0);
        }
    }
}
