package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseMemoryPublisherTest {

    @Test
    void storesApprovedCaseAsAgentScopedLongTermMemory() {
        List<LongTermMemoryPort.MemoryFact> stored = new ArrayList<>();
        CaseMemoryPublisher publisher = new CaseMemoryPublisher(new CapturingMemoryPort(stored));

        publisher.publish(caseItem(), "CONFIRMED", "审核通过，发布到业务 Case 看板");

        assertEquals(1, stored.size());
        LongTermMemoryPort.MemoryFact fact = stored.get(0);
        assertEquals("refund_agent", fact.agentId());
        assertEquals("refund_agent", fact.subjectId());
        assertEquals("PUBLISHED_CASE", fact.kind());
        assertTrue(fact.content().contains("退款审批规则缺失"));
        assertTrue(fact.content().contains("审核通过"));
    }

    @Test
    void ignoresNonPublishTransitions() {
        List<LongTermMemoryPort.MemoryFact> stored = new ArrayList<>();
        CaseMemoryPublisher publisher = new CaseMemoryPublisher(new CapturingMemoryPort(stored));

        publisher.publish(caseItem(), "PENDING_REVIEW", "提交审核");

        assertTrue(stored.isEmpty());
    }

    private AiCase caseItem() {
        return AiCase.builder()
                .agentId("refund_agent")
                .caseId("case-refund")
                .title("退款审批规则缺失")
                .summary("售后 Agent 缺少退款审批规则说明")
                .caseType("QUALITY")
                .severity("HIGH")
                .extractionReason("用户明确反馈业务规则不完整")
                .resolution("补充退款审批 Skill")
                .build();
    }

    private record CapturingMemoryPort(List<LongTermMemoryPort.MemoryFact> stored) implements LongTermMemoryPort {
        @Override public void store(MemoryFact fact) { stored.add(fact); }
        @Override public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) { return List.of(); }
    }
}
