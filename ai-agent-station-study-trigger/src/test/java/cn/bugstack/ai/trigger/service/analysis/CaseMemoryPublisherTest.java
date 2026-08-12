package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CaseMemoryPublisherTest {
    private final AgentMemoryLifecyclePort lifecycle = mock(AgentMemoryLifecyclePort.class);
    private final IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
    private final CaseMemoryPublisher publisher = new CaseMemoryPublisher(lifecycle, cardDao);

    @Test
    void resolvedCaseDirectlyPublishesResolvedCaseMemory() {
        publisher.publish(caseItem(), "RESOLVED", "developer confirmed the fix");

        verify(lifecycle).upsert(any(AgentMemoryLifecyclePort.UpsertCommand.class));
    }

    @Test
    void unresolvedCaseDoesNotWriteLongTermMemory() {
        publisher.publish(caseItem(), "CONFIRMED", "only confirmed");
        publisher.publish(caseItem(), "PENDING_REVIEW", "waiting for review");

        verifyNoInteractions(lifecycle);
    }

    @Test
    void reopenedCaseSoftDeletesItsResolvedMemory() {
        when(cardDao.queryPublishedByCaseId("refund_agent", "case-refund")).thenReturn(List.of(
                AgentMemoryCard.builder().memoryId("mem-refund").agentId("refund_agent").build()));

        publisher.publish(caseItem(), "IN_PROGRESS", "resolution needs recheck");

        verify(lifecycle).retire(any(AgentMemoryLifecyclePort.RetireCommand.class));
    }

    private AiCase caseItem() {
        return AiCase.builder().agentId("refund_agent").caseId("case-refund")
                .title("refund approval rule is incomplete").summary("service agent lacks refund approval rule")
                .caseType("QUALITY").severity("HIGH").status("RESOLVED")
                .extractionReason("feedback and business skill both confirm")
                .resolution("add refund approval skill").build();
    }
}
