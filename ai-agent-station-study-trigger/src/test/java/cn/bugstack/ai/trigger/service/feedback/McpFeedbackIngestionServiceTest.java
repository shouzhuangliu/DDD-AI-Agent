package cn.bugstack.ai.trigger.service.feedback;

import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import cn.bugstack.ai.trigger.service.agent.AgentBusinessContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpFeedbackIngestionServiceTest {

    private IAiFeedbackDao feedbackDao;
    private IAiCaseDao caseDao;
    private ICaseEvidenceDao caseEvidenceDao;
    private FeedbackEvaluationJobQueue evaluationJobQueue;
    private AgentBusinessContextService businessContextService;
    private McpFeedbackIngestionService service;

    @BeforeEach
    void setUp() {
        feedbackDao = mock(IAiFeedbackDao.class);
        caseDao = mock(IAiCaseDao.class);
        caseEvidenceDao = mock(ICaseEvidenceDao.class);
        evaluationJobQueue = mock(FeedbackEvaluationJobQueue.class);
        businessContextService = mock(AgentBusinessContextService.class);
        service = new McpFeedbackIngestionService();
        ReflectionTestUtils.setField(service, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(service, "caseDao", caseDao);
        ReflectionTestUtils.setField(service, "caseEvidenceDao", caseEvidenceDao);
        ReflectionTestUtils.setField(service, "feedbackEvaluationJobQueue", evaluationJobQueue);
        ReflectionTestUtils.setField(service, "agentBusinessContextService", businessContextService);
        when(businessContextService.boundBusinessSkillId("inventory-agent"))
                .thenReturn("inventory-feedback-agent");
        doAnswer(invocation -> {
            AiFeedback feedback = invocation.getArgument(0);
            feedback.setId(200L);
            return 1;
        }).when(feedbackDao).insert(any(AiFeedback.class));
    }

    @Test
    void importsSuccessfulTodayFeedbackAndIsIdempotent() {
        String args = "{\"mcpId\":\"inventory-feedback-mcp\",\"toolName\":\"get_today_feedback\",\"args\":\"{\\\"limit\\\":50}\"}";
        String content = "[{\"feedbackId\":\"fb-inv-20260804-001\",\"source\":\"USER\","
                + "\"service\":\"product-availability\",\"summary\":\"DDR5 缺货\","
                + "\"content\":\"DDR5 内存可下单但支付后缺货\",\"severityHint\":\"P1\","
                + "\"occurredAt\":\"2026-08-04T09:12:00+08:00\"}]";

        service.ingest("inventory-agent", "sess-1", 33L, "call_mcp_tool", args, content);

        ArgumentCaptor<AiFeedback> feedbackCaptor = ArgumentCaptor.forClass(AiFeedback.class);
        verify(feedbackDao).insert(feedbackCaptor.capture());
        assertEquals("inventory-agent", feedbackCaptor.getValue().getAgentId());
        assertEquals("USER", feedbackCaptor.getValue().getSourceType());
        assertEquals("OPEN", feedbackCaptor.getValue().getStatus());
        assertEquals("ISSUE_REPORT", feedbackCaptor.getValue().getFeedbackType());
        verify(evaluationJobQueue).enqueue("inventory-agent", 200L);

        when(feedbackDao.queryByAgentAndExternalRef("inventory-agent", "fb-inv-20260804-001"))
                .thenReturn(feedbackCaptor.getValue());
        service.ingest("inventory-agent", "sess-1", 34L, "call_mcp_tool", args, content);
        verify(feedbackDao, times(1)).insert(any(AiFeedback.class));
    }

    @Test
    void triagePromoteCreatesVisibleCandidateCaseAndLinksFeedback() {
        AiFeedback existing = AiFeedback.builder().id(200L).agentId("inventory-agent")
                .sessionId("sess-1").message("RTX 5060 频道页显示 12 件，后台只有 2 件")
                .sourceType("OPERATIONS").status("OPEN").correction("[mcp-feedback-id:fb-inv-20260804-002]")
                .build();
        when(feedbackDao.queryByAgentAndExternalRef("inventory-agent", "fb-inv-20260804-002"))
                .thenReturn(existing);
        when(caseDao.queryByAgentAndCaseId("inventory-agent", "case-mcp-fb-inv-20260804-002"))
                .thenReturn(null);

        String args = "{\"mcpId\":\"inventory-feedback-mcp\",\"toolName\":\"mark_feedback_triaged\","
                + "\"args\":\"{\\\"feedbackId\\\":\\\"fb-inv-20260804-002\\\","
                + "\\\"decision\\\":\\\"PROMOTE_CASE_PENDING_REVIEW\\\"}\"}";
        String content = "{\"triageId\":\"triage-abc123\",\"feedbackId\":\"fb-inv-20260804-002\","
                + "\"decision\":\"PROMOTE_CASE_PENDING_REVIEW\",\"note\":\"库存对账存在风险\"}";

        service.ingest("inventory-agent", "sess-1", 35L, "call_mcp_tool", args, content);

        verify(feedbackDao).transitionStatus(200L, "inventory-agent", "OPEN", "CLUSTERED",
                "MCP分诊升级", "case-mcp-fb-inv-20260804-002", 0);
        ArgumentCaptor<AiCase> caseCaptor = ArgumentCaptor.forClass(AiCase.class);
        verify(caseDao).insert(caseCaptor.capture());
        assertEquals("inventory-feedback-agent", caseCaptor.getValue().getSkillId());
        assertEquals("CANDIDATE", caseCaptor.getValue().getStatus());
        verify(caseEvidenceDao).insertIgnore(any());
    }

    @Test
    void runtimeMcpFailureDoesNotBecomeBusinessFeedbackOrCase() {
        service.ingest("inventory-agent", "sess-1", 36L, "call_mcp_tool",
                "{\"toolName\":\"get_today_feedback\"}", "MCP 调用异常: 连接已断开");

        verify(feedbackDao, never()).insert(any(AiFeedback.class));
        verify(caseDao, never()).insert(any(AiCase.class));
    }
}
