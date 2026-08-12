package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.trigger.service.memory.AgentMemoryCandidateService;
import org.springframework.stereotype.Component;

/**
 * Compatibility facade for the existing Case transition flow.
 */
@Component
public class CaseMemoryPublisher {

    private final AgentMemoryCandidateService candidateService;

    public CaseMemoryPublisher(AgentMemoryCandidateService candidateService) {
        this.candidateService = candidateService;
    }

    public void publish(AiCase item, String toStatus, String reason) {
        if (item == null || toStatus == null) return;
        String normalizedStatus = toStatus.trim().toUpperCase();
        if ("RESOLVED".equals(normalizedStatus)) candidateService.submitResolvedCaseCandidate(item, reason);
    }
}
