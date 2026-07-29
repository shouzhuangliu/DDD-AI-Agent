package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseMemoryPublisherTest {

    @Test
    void storesResolvedCaseInAgentProfile() {
        List<AgentMemoryProfile> profiles = new ArrayList<>();
        List<LongTermMemoryPort.MemoryFact> stored = new ArrayList<>();
        CaseMemoryPublisher publisher = publisher(profiles, stored);

        publisher.publish(caseItem(), "RESOLVED", "Verified by operator");

        assertEquals(1, profiles.size());
        assertEquals(1, profiles.get(0).getVersion());
        assertTrue(profiles.get(0).getProfileJson().contains("case-refund"));
        assertEquals(1, stored.size());
        assertEquals("refund_agent", stored.get(0).agentId());
        assertEquals("AGENT_PROFILE", stored.get(0).kind());
        assertEquals("case-refund", stored.get(0).sourceCaseId());
        assertEquals(1, stored.get(0).profileVersion());
    }

    @Test
    void repeatedResolvedCaseIsIdempotent() {
        List<AgentMemoryProfile> profiles = new ArrayList<>();
        List<LongTermMemoryPort.MemoryFact> stored = new ArrayList<>();
        CaseMemoryPublisher publisher = publisher(profiles, stored);

        publisher.publish(caseItem(), "RESOLVED", "Verified by operator");
        publisher.publish(caseItem(), "RESOLVED", "Retry after timeout");

        assertEquals(1, profiles.size());
        assertEquals(1, stored.size());
    }

    @Test
    void doesNotWriteUnresolvedCaseToProfile() {
        List<AgentMemoryProfile> profiles = new ArrayList<>();
        List<LongTermMemoryPort.MemoryFact> stored = new ArrayList<>();
        CaseMemoryPublisher publisher = publisher(profiles, stored);

        publisher.publish(caseItem(), "CONFIRMED", "Approved but not resolved");
        publisher.publish(caseItem(), "PENDING_REVIEW", "Submitted for review");

        assertTrue(profiles.isEmpty());
        assertTrue(stored.isEmpty());
    }

    private CaseMemoryPublisher publisher(List<AgentMemoryProfile> profiles,
                                           List<LongTermMemoryPort.MemoryFact> stored) {
        return new CaseMemoryPublisher(new AgentMemoryProfileService(
                new CapturingProfileDao(profiles), new CapturingMemoryPort(stored)));
    }

    private AiCase caseItem() {
        return AiCase.builder()
                .agentId("refund_agent")
                .caseId("case-refund")
                .title("Missing refund approval rule")
                .summary("The support Agent needs the refund approval rule.")
                .caseType("QUALITY")
                .severity("HIGH")
                .extractionReason("Confirmed by explicit user feedback")
                .resolution("Add refund approval skill")
                .build();
    }

    private static class CapturingProfileDao implements IAgentMemoryProfileDao {
        private final List<AgentMemoryProfile> profiles;

        private CapturingProfileDao(List<AgentMemoryProfile> profiles) {
            this.profiles = profiles;
        }

        @Override
        public AgentMemoryProfile queryLatest(String agentId) {
            return profiles.stream().filter(item -> agentId.equals(item.getAgentId()))
                    .max(java.util.Comparator.comparing(AgentMemoryProfile::getVersion)).orElse(null);
        }

        @Override
        public int insert(AgentMemoryProfile profile) {
            profiles.add(profile);
            return 1;
        }
    }

    private record CapturingMemoryPort(List<MemoryFact> stored) implements LongTermMemoryPort {
        @Override public void store(MemoryFact fact) { stored.add(fact); }
        @Override public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) { return List.of(); }
    }
}
