package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.infrastructure.dao.IAiCaseAuditDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IAiSignalDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.trigger.service.analysis.AgentMemoryProfileService;
import cn.bugstack.ai.trigger.service.analysis.CaseMemoryPublisher;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentOperationsControllerTest {

    private IAiFeedbackDao feedbackDao;
    private IAiCaseDao caseDao;
    private ICaseEvidenceDao caseEvidenceDao;
    private AgentOperationsController controller;

    @BeforeEach
    void setUp() {
        feedbackDao = mock(IAiFeedbackDao.class);
        caseDao = mock(IAiCaseDao.class);
        caseEvidenceDao = mock(ICaseEvidenceDao.class);
        controller = new AgentOperationsController();
        ReflectionTestUtils.setField(controller, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(controller, "caseDao", caseDao);
        ReflectionTestUtils.setField(controller, "chatMessageDao", mock(IChatMessageDao.class));
        ReflectionTestUtils.setField(controller, "signalDao", mock(IAiSignalDao.class));
        ReflectionTestUtils.setField(controller, "memorySummaryDao", mock(IMemorySummaryDao.class));
        ReflectionTestUtils.setField(controller, "memoryStateDao", mock(IMemoryStateDao.class));
        ReflectionTestUtils.setField(controller, "memoryToolResultDao", mock(IMemoryToolResultDao.class));
        ReflectionTestUtils.setField(controller, "caseEvidenceDao", caseEvidenceDao);
        ReflectionTestUtils.setField(controller, "caseAuditDao", mock(IAiCaseAuditDao.class));
        ReflectionTestUtils.setField(controller, "caseMemoryPublisher", mock(CaseMemoryPublisher.class));
        ReflectionTestUtils.setField(controller, "agentMemoryProfileService", mock(AgentMemoryProfileService.class));
        ReflectionTestUtils.setField(controller, "feedbackEvaluationJobQueue", mock(FeedbackEvaluationJobQueue.class));
    }

    @Test
    void rejectsPromotionWhenAiInferredFeedbackIsNotQualified() {
        when(feedbackDao.queryById(100L)).thenReturn(AiFeedback.builder()
                .id(100L)
                .agentId("cs")
                .status("OPEN")
                .sourceType("AI_INFERRED")
                .feedbackType("ISSUE_REPORT")
                .rating(1)
                .message("我感觉有问题")
                .build());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.transitionFeedback("cs", 100L,
                        new AgentOperationsController.FeedbackTransitionRequest("PROMOTED", "tester", "", "", "")));

        assertTrue(exception.getMessage().contains("尚未通过评测"));
        verify(caseDao, never()).insert(any());
    }

    @Test
    void allowsPromotionWhenValidatedFeedbackHasBusinessEvidence() {
        AiFeedback feedback = AiFeedback.builder()
                .id(101L)
                .agentId("cs")
                .sessionId("sess-1")
                .status("VALID")
                .sourceType("AI_INFERRED")
                .feedbackType("ISSUE_REPORT")
                .rating(1)
                .message("你们的db缓存不一致，商品显卡5060下单后列表还是没货")
                .category("业务问题反馈")
                .build();
        when(feedbackDao.queryById(101L)).thenReturn(feedback).thenReturn(AiFeedback.builder()
                .id(101L)
                .agentId("cs")
                .sessionId("sess-1")
                .status("PROMOTED")
                .sourceType("AI_INFERRED")
                .feedbackType("ISSUE_REPORT")
                .rating(1)
                .message("你们的db缓存不一致，商品显卡5060下单后列表还是没货")
                .category("业务问题反馈")
                .matchedCaseId("case-feedback-101")
                .resolved(1)
                .build());
        when(feedbackDao.transitionStatus(101L, "cs", "VALID", "PROMOTED", "", "case-feedback-101", 1)).thenReturn(1);
        when(caseDao.queryByAgentAndCaseId("cs", "case-feedback-101")).thenReturn(null);

        Map<String, Object> result = controller.transitionFeedback("cs", 101L,
                new AgentOperationsController.FeedbackTransitionRequest("PROMOTED", "tester", "跨会话重复出现，影响下单", "", ""));

        assertEquals(true, result.get("success"));
        assertEquals("case-feedback-101", result.get("caseId"));
        verify(caseDao).insert(any());
        verify(caseEvidenceDao).insertIgnore(any());
    }

    @Test
    void statsSeparatesBusinessFeedbackAiSignalsAndCaseStages() {
        when(feedbackDao.countExplicitByAgentId("cs")).thenReturn(8L);
        when(feedbackDao.countExplicitTodayByAgentId("cs")).thenReturn(3L);
        when(feedbackDao.countNegativeByAgentId("cs")).thenReturn(2L);
        when(feedbackDao.countAiObservedByAgentId("cs")).thenReturn(5L);
        when(feedbackDao.countReadyForCaseByAgentId("cs")).thenReturn(2L);
        when(caseDao.countByAgent("cs")).thenReturn(6L);
        when(caseDao.countByAgentAndStatus("cs", "CANDIDATE")).thenReturn(1L);
        when(caseDao.countByAgentAndStatus("cs", "PENDING_REVIEW")).thenReturn(2L);
        when(caseDao.countByAgentAndStatus("cs", "IN_PROGRESS")).thenReturn(1L);
        when(caseDao.countByAgentAndStatus("cs", "RESOLVED")).thenReturn(2L);

        Map<String, Object> result = controller.stats("cs");

        assertEquals(3L, result.get("todayFeedback"));
        assertEquals(8L, result.get("businessFeedback"));
        assertEquals(8L, result.get("explicitFeedback"));
        assertEquals(2L, result.get("negativeFeedback"));
        assertEquals(5L, result.get("aiObservationCount"));
        assertEquals(2L, result.get("readyForCaseFeedback"));
        assertEquals(1L, result.get("candidateCases"));
        assertEquals(2L, result.get("pendingCases"));
        assertEquals(1L, result.get("inProgressCases"));
        assertEquals(1L, result.get("highPriorityCases"));
        assertEquals(2L, result.get("resolvedCases"));
        assertEquals(75.0d, result.get("satisfactionRate"));
    }
}
