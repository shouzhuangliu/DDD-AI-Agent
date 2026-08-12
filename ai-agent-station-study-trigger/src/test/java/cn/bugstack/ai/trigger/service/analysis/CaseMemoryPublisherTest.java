package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.trigger.service.memory.AgentMemoryCandidateService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class CaseMemoryPublisherTest {

    private final AgentMemoryCandidateService candidateService = mock(AgentMemoryCandidateService.class);
    private final CaseMemoryPublisher publisher = new CaseMemoryPublisher(candidateService);

    @Test
    void resolvedCaseCreatesReviewableMemoryCandidate() {
        AiCase item = caseItem();
        publisher.publish(item, "RESOLVED", "开发人员确认修复");
        verify(candidateService).submitResolvedCaseCandidate(item, "开发人员确认修复");
    }

    @Test
    void unresolvedCaseDoesNotCreateMemoryCandidate() {
        publisher.publish(caseItem(), "CONFIRMED", "仅确认问题");
        publisher.publish(caseItem(), "PENDING_REVIEW", "等待审核");
        verifyNoInteractions(candidateService);
    }

    private AiCase caseItem() {
        return AiCase.builder().agentId("refund_agent").caseId("case-refund")
                .title("退款审批规则缺失").summary("客服 Agent 缺少退款审批规则")
                .caseType("QUALITY").severity("HIGH").status("RESOLVED")
                .extractionReason("用户反馈与业务 Skill 共同确认")
                .resolution("补充退款审批 Skill").build();
    }
}
