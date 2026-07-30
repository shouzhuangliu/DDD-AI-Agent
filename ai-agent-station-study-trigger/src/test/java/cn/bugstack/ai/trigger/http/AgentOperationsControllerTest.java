package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.infrastructure.dao.IAiCaseAuditDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiSignalDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import cn.bugstack.ai.trigger.service.analysis.AgentMemoryProfileService;
import cn.bugstack.ai.trigger.service.analysis.CaseMemoryPublisher;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import cn.bugstack.ai.trigger.service.memory.LongTermMemoryRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOperationsControllerTest {

    private IAiFeedbackDao feedbackDao;
    private IAiCaseDao caseDao;
    private ICaseEvidenceDao caseEvidenceDao;
    private AgentMemoryProfileService agentMemoryProfileService;
    private IMemorySummaryDao memorySummaryDao;
    private AgentOperationsController controller;

    @BeforeEach
    void setUp() {
        feedbackDao = mock(IAiFeedbackDao.class);
        caseDao = mock(IAiCaseDao.class);
        caseEvidenceDao = mock(ICaseEvidenceDao.class);
        agentMemoryProfileService = mock(AgentMemoryProfileService.class);
        memorySummaryDao = mock(IMemorySummaryDao.class);
        controller = new AgentOperationsController();
        ReflectionTestUtils.setField(controller, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(controller, "caseDao", caseDao);
        ReflectionTestUtils.setField(controller, "chatMessageDao", mock(IChatMessageDao.class));
        ReflectionTestUtils.setField(controller, "signalDao", mock(IAiSignalDao.class));
        ReflectionTestUtils.setField(controller, "memorySummaryDao", memorySummaryDao);
        ReflectionTestUtils.setField(controller, "memoryStateDao", mock(IMemoryStateDao.class));
        ReflectionTestUtils.setField(controller, "memoryToolResultDao", mock(IMemoryToolResultDao.class));
        ReflectionTestUtils.setField(controller, "caseEvidenceDao", caseEvidenceDao);
        ReflectionTestUtils.setField(controller, "caseAuditDao", mock(IAiCaseAuditDao.class));
        ReflectionTestUtils.setField(controller, "caseMemoryPublisher", mock(CaseMemoryPublisher.class));
        ReflectionTestUtils.setField(controller, "agentMemoryProfileService", agentMemoryProfileService);
        ReflectionTestUtils.setField(controller, "feedbackEvaluationJobQueue", mock(FeedbackEvaluationJobQueue.class));
        ReflectionTestUtils.setField(controller, "longTermMemoryRecallService", mock(LongTermMemoryRecallService.class));
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

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> controller.transitionFeedback("cs", 100L,
                        new AgentOperationsController.FeedbackTransitionRequest("PROMOTED", "tester", "", "", "")));

        assertTrue(exception.getMessage().contains("反馈"));
        verify(caseDao, never()).insert(any());
    }

    @Test
    void rejectsPromotionBeforeFeedbackFinishesEvaluationFlow() {
        when(feedbackDao.queryById(110L)).thenReturn(AiFeedback.builder()
                .id(110L)
                .agentId("cs")
                .status("OPEN")
                .sourceType("EXPLICIT")
                .feedbackType("ISSUE_REPORT")
                .rating(1)
                .message("用户反馈商品库存显示异常，希望尽快处理")
                .build());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> controller.transitionFeedback("cs", 110L,
                        new AgentOperationsController.FeedbackTransitionRequest("PROMOTED", "tester", "想直接升级", "", "")));

        assertTrue(exception.getMessage().contains("反馈"));
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
                .message("你们的 db 缓存不一致，商品显卡5060下单后列表还是没货")
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
                .message("你们的 db 缓存不一致，商品显卡5060下单后列表还是没货")
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

    @Test
    void feedbackViewExposesBackendAvailableActionsInChinese() {
        when(feedbackDao.queryWorkspaceByAgentId("cs", 50)).thenReturn(List.of(
                AiFeedback.builder()
                        .id(201L)
                        .agentId("cs")
                        .status("VALID")
                        .sourceType("USER")
                        .feedbackType("ISSUE_REPORT")
                        .message("商品库存显示异常，订单号5060在页面和接口返回不一致")
                        .category("业务问题反馈")
                        .build()
        ));

        List<Map<String, Object>> result = controller.feedback("cs", 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.getFirst().get("availableActions");

        assertEquals("升级为 Case", actions.getFirst().get("label"));
        assertEquals("PROMOTED", actions.getFirst().get("status"));
        assertEquals("进入候选问题簇", actions.get(1).get("label"));
    }

    @Test
    void feedbackViewHidesPromoteActionWhenEvidenceIsInsufficient() {
        when(feedbackDao.queryWorkspaceByAgentId("cs", 50)).thenReturn(List.of(
                AiFeedback.builder()
                        .id(202L)
                        .agentId("cs")
                        .status("VALID")
                        .sourceType("USER")
                        .feedbackType("ISSUE_REPORT")
                        .message("系统有问题，请处理一下")
                        .category("业务问题反馈")
                        .build()
        ));

        List<Map<String, Object>> result = controller.feedback("cs", 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.getFirst().get("availableActions");

        assertEquals(false, result.getFirst().get("promotionEligible"));
        assertTrue(actions.stream().noneMatch(action -> "PROMOTED".equals(action.get("status"))));
        assertEquals("进入候选问题簇", actions.getFirst().get("label"));
    }

    @Test
    void caseViewExposesBackendAvailableActionsInChinese() {
        when(caseDao.queryByAgentAndStatus("cs", "CANDIDATE", 50)).thenReturn(List.of(
                AiCase.builder()
                        .caseId("case-001")
                        .agentId("cs")
                        .title("库存异常")
                        .status("CANDIDATE")
                        .build()
        ));

        List<?> result = controller.cases("cs", "CANDIDATE", 50);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) result.getFirst();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) first.get("availableActions");

        assertEquals("提交审核", actions.getFirst().get("label"));
        assertEquals("PENDING_REVIEW", actions.getFirst().get("status"));
        assertEquals("候选问题", first.get("statusLabel"));
        assertEquals("合并到其他 Case", actions.get(2).get("label"));
        assertEquals("MERGE", actions.get(2).get("operation"));
    }

    @Test
    void workspaceOverviewAggregatesStatsFeedbackCasesAndMemory() {
        when(feedbackDao.countExplicitByAgentId("cs")).thenReturn(4L);
        when(feedbackDao.countExplicitTodayByAgentId("cs")).thenReturn(2L);
        when(feedbackDao.countNegativeByAgentId("cs")).thenReturn(1L);
        when(feedbackDao.countAiObservedByAgentId("cs")).thenReturn(3L);
        when(feedbackDao.countReadyForCaseByAgentId("cs")).thenReturn(1L);
        when(caseDao.countByAgent("cs")).thenReturn(2L);
        when(caseDao.countByAgentAndStatus("cs", "CANDIDATE")).thenReturn(1L);
        when(caseDao.countByAgentAndStatus("cs", "PENDING_REVIEW")).thenReturn(0L);
        when(caseDao.countByAgentAndStatus("cs", "IN_PROGRESS")).thenReturn(1L);
        when(caseDao.countByAgentAndStatus("cs", "RESOLVED")).thenReturn(0L);
        when(feedbackDao.queryWorkspaceByAgentId("cs", 5)).thenReturn(List.of(
                AiFeedback.builder()
                        .id(301L)
                        .agentId("cs")
                        .status("VALID")
                        .sourceType("USER")
                        .feedbackType("ISSUE_REPORT")
                        .message("DDR5 商品缺货，希望补货")
                        .build()
        ));
        when(caseDao.queryTopByAgent("cs", 5)).thenReturn(List.of(
                AiCase.builder().caseId("case-1").agentId("cs").title("DDR5 补货").status("CANDIDATE").build()
        ));
        when(caseDao.queryByAgentAndStatus("cs", "CANDIDATE", 5)).thenReturn(List.of(
                AiCase.builder().caseId("case-1").agentId("cs").title("DDR5 补货").status("CANDIDATE").build()
        ));
        when(agentMemoryProfileService.latest("cs")).thenReturn(AgentMemoryProfile.builder()
                .agentId("cs").version(2).profileJson("{\"preferences\":[]}").build());
        when(memorySummaryDao.queryByAgent("cs", 3)).thenReturn(List.of(
                MemorySummary.builder().agentId("cs").sessionId("sess-1").summary("用户反馈 DDR5 商品缺货").build()
        ));

        Map<String, Object> overview = controller.workspaceOverview("cs", 5, 5, 3);

        assertEquals("cs", overview.get("agentId"));
        assertTrue(overview.containsKey("generatedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) overview.get("stats");
        assertEquals(4L, stats.get("businessFeedback"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentFeedback = (List<Map<String, Object>>) overview.get("recentFeedback");
        assertEquals(1, recentFeedback.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topCases = (List<Map<String, Object>>) overview.get("topCases");
        assertEquals(1, topCases.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> memoryProfile = (Map<String, Object>) overview.get("memoryProfile");
        assertEquals("cs", memoryProfile.get("agentId"));
        @SuppressWarnings("unchecked")
        List<MemorySummary> recentMemorySummaries = (List<MemorySummary>) overview.get("recentMemorySummaries");
        assertEquals(1, recentMemorySummaries.size());
    }
}
