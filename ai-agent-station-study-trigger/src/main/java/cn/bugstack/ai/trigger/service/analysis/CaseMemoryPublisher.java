package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import org.springframework.stereotype.Component;

/**
 * Compatibility facade for the existing Case transition flow.
 */
@Component
public class CaseMemoryPublisher {

    private final AgentMemoryProfileService profileService;

    public CaseMemoryPublisher(AgentMemoryProfileService profileService) {
        this.profileService = profileService;
    }

    public void publish(AiCase item, String toStatus, String reason) {
        if (item == null || toStatus == null) return;
        String normalizedStatus = toStatus.trim().toUpperCase();
        if ("RESOLVED".equals(normalizedStatus)) profileService.updateFromResolvedCase(item, reason);
    }
}
