package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAiCaseAuditDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IAiSignalDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.ICaseScoreSnapshotDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import cn.bugstack.ai.trigger.service.analysis.AgentMemoryProfileService;
import cn.bugstack.ai.trigger.service.analysis.CaseMemoryPublisher;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import cn.bugstack.ai.trigger.service.agent.AgentBusinessContextService;
import cn.bugstack.ai.trigger.service.conversation.ConversationSessionService;
import cn.bugstack.ai.trigger.service.memory.LongTermMemoryRecallService;
import cn.bugstack.ai.trigger.service.memory.MemoryQueryAdmissionPolicy;
import cn.bugstack.ai.trigger.service.observability.ConversationTraceService;
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
    private IAiCaseAuditDao caseAuditDao;
    private ICaseScoreSnapshotDao caseScoreSnapshotDao;
    private ICaseEvidenceDao caseEvidenceDao;
    private IAiLlmLogDao llmLogDao;
    private AgentMemoryProfileService agentMemoryProfileService;
    private IMemorySummaryDao memorySummaryDao;
    private IAiSessionDao sessionDao;
    private LongTermMemoryPort longTermMemoryPort;
    private ConversationSessionService conversationSessionService;
    private ConversationTraceService conversationTraceService;
    private AgentRuntimeBindingService agentRuntimeBindingService;
    private AgentBusinessContextService agentBusinessContextService;
    private AgentOperationsController controller;

    @BeforeEach
    void setUp() {
        feedbackDao = mock(IAiFeedbackDao.class);
        caseDao = mock(IAiCaseDao.class);
        caseAuditDao = mock(IAiCaseAuditDao.class);
        caseScoreSnapshotDao = mock(ICaseScoreSnapshotDao.class);
        caseEvidenceDao = mock(ICaseEvidenceDao.class);
        llmLogDao = mock(IAiLlmLogDao.class);
        agentMemoryProfileService = mock(AgentMemoryProfileService.class);
        memorySummaryDao = mock(IMemorySummaryDao.class);
        sessionDao = mock(IAiSessionDao.class);
        longTermMemoryPort = mock(LongTermMemoryPort.class);
        conversationSessionService = mock(ConversationSessionService.class);
        conversationTraceService = mock(ConversationTraceService.class);
        agentRuntimeBindingService = mock(AgentRuntimeBindingService.class);
        agentBusinessContextService = mock(AgentBusinessContextService.class);
        when(agentBusinessContextService.hasBoundBusinessSkill(any())).thenReturn(true);
        when(agentBusinessContextService.boundBusinessSkillId(any())).thenReturn("inventory-feedback-agent");
        controller = new AgentOperationsController();
        ReflectionTestUtils.setField(controller, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(controller, "caseDao", caseDao);
        ReflectionTestUtils.setField(controller, "chatMessageDao", mock(IChatMessageDao.class));
        ReflectionTestUtils.setField(controller, "llmLogDao", llmLogDao);
        ReflectionTestUtils.setField(controller, "signalDao", mock(IAiSignalDao.class));
        ReflectionTestUtils.setField(controller, "memorySummaryDao", memorySummaryDao);
        ReflectionTestUtils.setField(controller, "memoryStateDao", mock(IMemoryStateDao.class));
        ReflectionTestUtils.setField(controller, "memoryToolResultDao", mock(IMemoryToolResultDao.class));
        ReflectionTestUtils.setField(controller, "caseEvidenceDao", caseEvidenceDao);
        ReflectionTestUtils.setField(controller, "caseAuditDao", caseAuditDao);
        ReflectionTestUtils.setField(controller, "caseScoreSnapshotDao", caseScoreSnapshotDao);
        ReflectionTestUtils.setField(controller, "sessionDao", sessionDao);
        ReflectionTestUtils.setField(controller, "caseMemoryPublisher", mock(CaseMemoryPublisher.class));
        ReflectionTestUtils.setField(controller, "agentMemoryProfileService", agentMemoryProfileService);
        ReflectionTestUtils.setField(controller, "feedbackEvaluationJobQueue", mock(FeedbackEvaluationJobQueue.class));
        ReflectionTestUtils.setField(controller, "longTermMemoryRecallService", mock(LongTermMemoryRecallService.class));
        ReflectionTestUtils.setField(controller, "longTermMemoryPort", longTermMemoryPort);
        ReflectionTestUtils.setField(controller, "memoryQueryAdmissionPolicy", new MemoryQueryAdmissionPolicy());
        ReflectionTestUtils.setField(controller, "conversationSessionService", conversationSessionService);
        ReflectionTestUtils.setField(controller, "conversationTraceService", conversationTraceService);
        ReflectionTestUtils.setField(controller, "agentRuntimeBindingService", agentRuntimeBindingService);
        ReflectionTestUtils.setField(controller, "agentBusinessContextService", agentBusinessContextService);
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
        var insertedCase = org.mockito.ArgumentCaptor.forClass(AiCase.class);
        verify(caseDao).insert(insertedCase.capture());
        assertEquals("inventory-feedback-agent", insertedCase.getValue().getSkillId(),
                "升级生成的 Case 必须绑定当前 Agent 的业务 Skill，才能进入仪表盘");
        verify(caseEvidenceDao).insertIgnore(any());
        verify(caseScoreSnapshotDao).insert(any());
        verify(caseAuditDao).insertReview("case-feedback-101", "cs", "NEW", "PROMOTED", "system", "跨会话重复出现，影响下单");
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

    @Test
    void reviewQueueAggregatesFeedbackCasesAndRecentSessions() {
        when(feedbackDao.queryWorkspaceByAgentId("cs", 3)).thenReturn(List.of(
                AiFeedback.builder().id(401L).agentId("cs").status("OPEN").sourceType("USER")
                        .feedbackType("ISSUE_REPORT").message("DDR5 商品缺货").build(),
                AiFeedback.builder().id(402L).agentId("cs").status("PROMOTED").sourceType("USER")
                        .feedbackType("ISSUE_REPORT").message("已升级成 Case").build()
        ));
        when(caseDao.queryByAgentAndStatus("cs", "CANDIDATE", 3)).thenReturn(List.of(
                AiCase.builder().caseId("case-a").agentId("cs").title("候选问题").status("CANDIDATE").build()
        ));
        when(caseDao.queryByAgentAndStatus("cs", "PENDING_REVIEW", 3)).thenReturn(List.of(
                AiCase.builder().caseId("case-b").agentId("cs").title("待审核问题").status("PENDING_REVIEW").build()
        ));
        when(caseDao.queryByAgentAndStatus("cs", "IN_PROGRESS", 3)).thenReturn(List.of(
                AiCase.builder().caseId("case-c").agentId("cs").title("处理中问题").status("IN_PROGRESS").build()
        ));
        when(sessionDao.queryByAgentId("cs", 3)).thenReturn(List.of(
                AiSession.builder().sessionId("sess-1").agentId("cs").title("补货对话")
                        .preview("用户反馈 DDR5 商品缺货").messageCount(6).modelId("deepseek-v4")
                        .status(1).build()
        ));

        Map<String, Object> queue = controller.reviewQueue("cs", 3);

        assertEquals("cs", queue.get("agentId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> feedbackQueue = (List<Map<String, Object>>) queue.get("feedbackQueue");
        assertEquals(1, feedbackQueue.size());
        assertEquals("OPEN", feedbackQueue.getFirst().get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidateCases = (List<Map<String, Object>>) queue.get("candidateCases");
        assertEquals(1, candidateCases.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pendingReviewCases = (List<Map<String, Object>>) queue.get("pendingReviewCases");
        assertEquals(1, pendingReviewCases.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inProgressCases = (List<Map<String, Object>>) queue.get("inProgressCases");
        assertEquals(1, inProgressCases.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentSessions = (List<Map<String, Object>>) queue.get("recentSessions");
        assertEquals(1, recentSessions.size());
        assertEquals("补货对话", recentSessions.getFirst().get("title"));
    }

    @Test
    void memoryGovernanceExplainsReadinessAndSummaryAdmission() {
        when(agentMemoryProfileService.latest("cs")).thenReturn(AgentMemoryProfile.builder()
                .agentId("cs").version(3).sourceCaseIds("case-1,case-2").build());
        when(memorySummaryDao.queryByAgent("cs", 3)).thenReturn(List.of(
                MemorySummary.builder().agentId("cs").sessionId("sess-1").version(1)
                        .summary("用户反馈 DDR5 商品长期缺货，希望补货并排查供应规则。").status("ACTIVE").build(),
                MemorySummary.builder().agentId("cs").sessionId("sess-2").version(1)
                        .summary("好的").status("ACTIVE").build()
        ));
        LongTermMemoryRecallService recallService = mock(LongTermMemoryRecallService.class);
        when(recallService.recall("cs", "DDR5 补货", 3)).thenReturn(List.of(
                new LongTermMemoryRecallService.MemoryRecallItem("cs", "SESSION_SUMMARY", "sess-1", "短期折叠摘要",
                        "用户反馈 DDR5 商品长期缺货，希望补货并排查供应规则。", 82d, "sess-1", "", 1, null, Map.of())
        ));
        ReflectionTestUtils.setField(controller, "longTermMemoryRecallService", recallService);
        ReflectionTestUtils.setField(controller, "longTermMemoryPort", new LongTermMemoryPort() {
            @Override
            public void store(MemoryFact fact) {
            }

            @Override
            public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) {
                return List.of();
            }
        });

        Map<String, Object> governance = controller.memoryGovernance("cs", "DDR5 补货", 3);

        assertEquals("cs", governance.get("agentId"));
        assertEquals("LongTermMemoryPort", governance.get("provider"));
        assertEquals("READY", governance.get("readiness"));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) governance.get("policy");
        @SuppressWarnings("unchecked")
        Map<String, Object> recall = (Map<String, Object>) policy.get("recall");
        assertEquals(true, recall.get("allowed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) governance.get("profile");
        assertEquals(2, profile.get("sourceCaseCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentSummaryReadiness =
                (List<Map<String, Object>>) governance.get("recentSummaryReadiness");
        assertEquals(2, recentSummaryReadiness.size());
        assertEquals(true, recentSummaryReadiness.getFirst().get("eligibleForLongTerm"));
        assertEquals(false, recentSummaryReadiness.get(1).get("eligibleForLongTerm"));
        @SuppressWarnings("unchecked")
        List<LongTermMemoryRecallService.MemoryRecallItem> recallPreview =
                (List<LongTermMemoryRecallService.MemoryRecallItem>) governance.get("recallPreview");
        assertEquals(1, recallPreview.size());
    }

    @Test
    void sessionWorkbenchAggregatesDetailAndTrace() {
        when(conversationSessionService.detail("cs", "sess-1")).thenReturn(Map.of(
                "session", Map.of("sessionId", "sess-1", "title", "DDR5 补货会话"),
                "overview", Map.of("messageCount", 4, "feedbackCount", 1),
                "memory", Map.of("summary", Map.of("summary", "用户反馈 DDR5 商品长期缺货")),
                "feedback", List.of(Map.of("id", 1L, "status", "VALID")),
                "cases", List.of(Map.of("caseId", "case-1", "status", "CANDIDATE")),
                "subagents", List.of(Map.of("taskId", "sub-1", "status", "SUCCESS")),
                "messages", List.of(Map.of("id", 11L, "role", "user")),
                "timeline", List.of(Map.of("type", "FEEDBACK", "status", "VALID"))
        ));
        when(conversationTraceService.trace("cs", "sess-1")).thenReturn(
                new ConversationTraceService.ConversationTrace(
                        "cs",
                        "sess-1",
                        new ConversationTraceService.TraceSummary(4, 2, 1, 1, 1, false),
                        List.of(
                                new ConversationTraceService.TimelineEvent("USER_MESSAGE", "用户消息", null,
                                        "SUCCESS", 11L, null, "", "", "", "你好", "", Map.of(), 10),
                                new ConversationTraceService.TimelineEvent("LLM_CALL", "模型调用", null,
                                        "SUCCESS", null, 100L, "", "", "", "完成补货建议", "", Map.of(), 20),
                                new ConversationTraceService.TimelineEvent("FEEDBACK", "反馈", null,
                                        "VALID", null, null, "", "", "", "DDR5 缺货", "", Map.of(), 50)
                        )
                )
        );

        Map<String, Object> workbench = controller.sessionWorkbench("cs", "sess-1");

        assertEquals("cs", workbench.get("agentId"));
        assertEquals("sess-1", workbench.get("sessionId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) workbench.get("session");
        assertEquals("DDR5 补货会话", session.get("title"));
        @SuppressWarnings("unchecked")
        Map<String, Long> eventTypeCounts = (Map<String, Long>) workbench.get("eventTypeCounts");
        assertEquals(1L, eventTypeCounts.get("USER_MESSAGE"));
        assertEquals(1L, eventTypeCounts.get("LLM_CALL"));
        assertEquals(1L, eventTypeCounts.get("FEEDBACK"));
        @SuppressWarnings("unchecked")
        Map<String, Long> eventStatusCounts = (Map<String, Long>) workbench.get("eventStatusCounts");
        assertEquals(2L, eventStatusCounts.get("SUCCESS"));
        assertEquals(1L, eventStatusCounts.get("VALID"));
    }

    @Test
    void runtimeAuditAggregatesBindingsExecutionsAndLlmCalls() {
        when(agentRuntimeBindingService.assemble("cs", ".", false)).thenReturn(
                AgentRuntimeBindingService.AgentRuntimeBindings.builder()
                        .agent(AiAgentVO.builder().agentId("cs").modelId("deepseek-v4-flash").channel("react").build())
                        .workspace(java.nio.file.Path.of("D:/runtime/cs"))
                        .skillIds(List.of("demo-skill"))
                        .mcpIds(List.of("enterprise-demo-mcp"))
                        .explicitToolIds(List.of("task"))
                        .effectiveToolIds(List.of("task", "dispatch_subagents", "read_file", "call_mcp_tool"))
                        .skillMetadataById(Map.of(
                                "demo-skill",
                                SkillScannerService.SkillInfo.builder()
                                        .skillId("demo-skill")
                                        .skillName("Demo Skill")
                                        .description("演示技能")
                                        .content("")
                                        .build()
                        ))
                        .mcpTools(List.of(
                                AiClientToolMcpVO.builder()
                                        .mcpId("enterprise-demo-mcp")
                                        .mcpName("Enterprise Demo MCP")
                                        .transportType("sse")
                                        .build()
                        ))
                        .build()
        );
        when(sessionDao.queryByAgentId("cs", 3)).thenReturn(List.of(
                AiSession.builder().sessionId("sess-1").agentId("cs").title("运行时审计会话")
                        .preview("用户反馈 DDR5 商品缺货").modelId("deepseek-v4-flash").messageCount(5).status(1).build()
        ));
        when(conversationSessionService.detail("cs", "sess-1")).thenReturn(Map.of(
                "overview", Map.of(
                        "latestRouteType", "feedback",
                        "latestExecutionStatus", "SUCCESS",
                        "latestModelId", "deepseek-v4-flash",
                        "latestExecutionId", "exec-1",
                        "latestExecutionAt", java.time.LocalDateTime.of(2026, 7, 30, 14, 55),
                        "latestExecutionStep", 3,
                        "latestExecutionStateJson", "{\"todos\":[{\"content\":\"记录反馈\"},{\"content\":\"评测升级\"}]}"
                )
        ));
        when(conversationTraceService.trace("cs", "sess-1")).thenReturn(
                new ConversationTraceService.ConversationTrace(
                        "cs",
                        "sess-1",
                        new ConversationTraceService.TraceSummary(5, 2, 3, 1, 1, false),
                        List.of()
                )
        );
        when(llmLogDao.queryByAgentId("cs", 4)).thenReturn(List.of(
                AiLlmLog.builder().id(900L).agentId("cs").sessionId("sess-1").modelName("deepseek-v4-flash")
                        .mode("react").status("success").durationMs(320).totalTokens(1280)
                        .historyMsgCount(4).foldedMsgCount(1).systemPromptLen(800).userMessageLen(66)
                        .assistantResponseLen(188).createdAt(null).build()
        ));

        Map<String, Object> audit = controller.runtimeAudit("cs", 3, 4);

        assertEquals("cs", audit.get("agentId"));
        assertEquals("D:/runtime/cs", String.valueOf(audit.get("workspace")).replace('\\', '/'));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeBindings = (Map<String, Object>) audit.get("runtimeBindings");
        assertEquals("deepseek-v4-flash", runtimeBindings.get("modelId"));
        assertEquals("react", runtimeBindings.get("channel"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> effectiveTools = (List<Map<String, Object>>) runtimeBindings.get("effectiveTools");
        assertEquals(4, effectiveTools.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentExecutions = (List<Map<String, Object>>) audit.get("recentExecutions");
        assertEquals(1, recentExecutions.size());
        assertEquals("feedback", recentExecutions.getFirst().get("routeType"));
        assertEquals(2, recentExecutions.getFirst().get("todoCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentLlmCalls = (List<Map<String, Object>>) audit.get("recentLlmCalls");
        assertEquals(1, recentLlmCalls.size());
        assertEquals(1280, recentLlmCalls.getFirst().get("totalTokens"));
    }

    @Test
    void feedbackPromotionAuditReturnsReadinessBreakdownAndLinkedCaseAudit() {
        AiFeedback feedback = AiFeedback.builder()
                .id(501L)
                .agentId("cs")
                .status("PROMOTED")
                .sourceType("USER")
                .feedbackType("ISSUE_REPORT")
                .rating(1)
                .message("DDR5 商品长期缺货，希望尽快补货")
                .matchedCaseId("case-feedback-501")
                .build();
        when(feedbackDao.queryById(501L)).thenReturn(feedback);
        when(caseDao.queryByAgentAndCaseId("cs", "case-feedback-501")).thenReturn(
                AiCase.builder().caseId("case-feedback-501").agentId("cs").title("DDR5 缺货").status("CANDIDATE").build()
        );
        when(caseAuditDao.queryScoreSnapshots("cs", "case-feedback-501")).thenReturn(List.of(
                Map.of("case_id", "case-feedback-501", "total_score", 74.2d)
        ));
        when(caseAuditDao.queryReviews("cs", "case-feedback-501")).thenReturn(List.of(
                Map.of("case_id", "case-feedback-501", "to_status", "PROMOTED", "actor", "tester")
        ));

        Map<String, Object> result = controller.feedbackPromotionAudit("cs", 501L);

        assertEquals("cs", result.get("agentId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) result.get("readiness");
        assertEquals(false, readiness.get("eligible"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scoreBreakdown = (Map<String, Object>) result.get("scoreBreakdown");
        assertTrue(((Number) scoreBreakdown.get("totalScore")).doubleValue() > 0d);
        @SuppressWarnings("unchecked")
        Map<String, Object> linkedCase = (Map<String, Object>) result.get("linkedCase");
        assertEquals("case-feedback-501", linkedCase.get("caseId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scoreSnapshots = (List<Map<String, Object>>) result.get("scoreSnapshots");
        assertEquals(1, scoreSnapshots.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) result.get("reviews");
        assertEquals(1, reviews.size());
    }
}
