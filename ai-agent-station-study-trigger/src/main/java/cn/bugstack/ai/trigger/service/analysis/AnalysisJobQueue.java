package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import cn.bugstack.ai.infrastructure.dao.IAnalysisJobDao;
import cn.bugstack.ai.infrastructure.dao.po.AnalysisJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AnalysisJobQueue {

    public static final String POLICY_VERSION = "v1";

    @Resource private IAnalysisJobDao analysisJobDao;
    @Resource private IAgentRepository agentRepository;

    public void enqueue(String agentId, String sessionId, Long assistantMessageId) {
        if (assistantMessageId == null) return;
        AiAgentVO agent = agentRepository.queryAgentById(agentId);
        String modelId = ModelSelectionService.select(null, agent == null ? null : agent.getModelId());
        LocalDateTime now = LocalDateTime.now();
        analysisJobDao.insertIgnore(AnalysisJob.builder()
                .idempotencyKey("conversation-analysis:" + POLICY_VERSION + ":" + assistantMessageId)
                .agentId(agentId).sessionId(sessionId).assistantMessageId(assistantMessageId)
                .policyVersion(POLICY_VERSION).modelId(modelId).status("PENDING")
                .attempts(0).maxAttempts(3).createdAt(now).updatedAt(now).build());
    }
}
