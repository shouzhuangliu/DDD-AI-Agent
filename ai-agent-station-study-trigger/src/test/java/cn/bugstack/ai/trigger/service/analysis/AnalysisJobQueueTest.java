package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.infrastructure.dao.IAnalysisJobDao;
import cn.bugstack.ai.infrastructure.dao.po.AnalysisJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisJobQueueTest {

    private IAnalysisJobDao jobDao;
    private AnalysisJobQueue queue;

    @BeforeEach
    void setUp() {
        jobDao = mock(IAnalysisJobDao.class);
        IAgentRepository agentRepository = mock(IAgentRepository.class);
        when(agentRepository.queryAgentById("inventory-agent"))
                .thenReturn(AiAgentVO.builder().modelId("2001").build());
        queue = new AnalysisJobQueue();
        ReflectionTestUtils.setField(queue, "analysisJobDao", jobDao);
        ReflectionTestUtils.setField(queue, "agentRepository", agentRepository);
    }

    @Test
    void normalConversationUsesSlidingIdleDelay() {
        queue.enqueue("inventory-agent", "session-1", 10L, false);

        var captor = org.mockito.ArgumentCaptor.forClass(AnalysisJob.class);
        verify(jobDao).insertIgnore(captor.capture());
        AnalysisJob job = captor.getValue();
        assertTrue(Duration.between(job.getCreatedAt(), job.getAvailableAt()).compareTo(
                AnalysisJobQueue.IDLE_DELAY) >= 0);
        verify(jobDao).refreshPendingSession("inventory-agent", "session-1",
                AnalysisJobQueue.POLICY_VERSION, 10L, job.getAvailableAt());
    }

    @Test
    void explicitBusinessFeedbackIsAvailableImmediately() {
        queue.enqueue("inventory-agent", "session-1", 11L, true);

        var captor = org.mockito.ArgumentCaptor.forClass(AnalysisJob.class);
        verify(jobDao).insertIgnore(captor.capture());
        AnalysisJob job = captor.getValue();
        assertTrue(Duration.between(job.getCreatedAt(), job.getAvailableAt()).abs()
                .compareTo(Duration.ofSeconds(2)) <= 0);
    }
}
