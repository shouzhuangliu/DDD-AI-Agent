package cn.bugstack.ai.trigger.service.conversation;

import cn.bugstack.ai.infrastructure.dao.IAgentExecutionDao;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ConversationSessionServiceTest {

    private IAgentExecutionDao executionDao;
    private IAiAgentDao agentDao;
    private IAiCaseDao caseDao;
    private IAiFeedbackDao feedbackDao;
    private IAiSessionDao sessionDao;
    private ConversationSessionService service;

    @BeforeEach
    void setUp() {
        executionDao = mock(IAgentExecutionDao.class);
        agentDao = mock(IAiAgentDao.class);
        caseDao = mock(IAiCaseDao.class);
        feedbackDao = mock(IAiFeedbackDao.class);
        sessionDao = mock(IAiSessionDao.class);
        service = new ConversationSessionService();
        ReflectionTestUtils.setField(service, "executionDao", executionDao);
        ReflectionTestUtils.setField(service, "agentDao", agentDao);
        ReflectionTestUtils.setField(service, "caseDao", caseDao);
        ReflectionTestUtils.setField(service, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(service, "sessionDao", sessionDao);
        ReflectionTestUtils.setField(service, "messageDao", mock(IChatMessageDao.class));
        ReflectionTestUtils.setField(service, "summaryDao", mock(IMemorySummaryDao.class));
        ReflectionTestUtils.setField(service, "stateDao", mock(IMemoryStateDao.class));
        ReflectionTestUtils.setField(service, "toolResultDao", mock(IMemoryToolResultDao.class));
    }

    @Test
    void renameOwnedSessionUsesSanitizedTitle() {
        when(sessionDao.queryByAgentAndSession("auto_agent", "sess-1")).thenReturn(AiSession.builder()
                .agentId("auto_agent").sessionId("sess-1").status(1).build());

        AiSession renamed = service.rename("auto_agent", "sess-1", "  企业级记忆设计  ");

        assertEquals("企业级记忆设计", renamed.getTitle());
        verify(sessionDao).updateTitle("sess-1", "企业级记忆设计");
    }

    @Test
    void renameRejectsSessionFromAnotherAgent() {
        when(sessionDao.queryByAgentAndSession("auto_agent", "sess-2")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.rename("auto_agent", "sess-2", "新标题"));

        verify(sessionDao, never()).updateTitle(anyString(), anyString());
    }

    @Test
    void deleteOwnedSessionSoftDeletesAndListHidesDeletedSessions() {
        when(agentDao.queryByAgentId("auto_agent")).thenReturn(AiAgent.builder().agentId("auto_agent").build());
        when(sessionDao.queryByAgentAndSession("auto_agent", "sess-3")).thenReturn(AiSession.builder()
                .agentId("auto_agent").sessionId("sess-3").status(1).build());
        when(sessionDao.queryByAgentId("auto_agent", 50)).thenReturn(List.of(
                AiSession.builder().sessionId("active").status(1).build(),
                AiSession.builder().sessionId("deleted").status(0).build()));

        AiSession deleted = service.delete("auto_agent", "sess-3");
        List<AiSession> visible = service.list("auto_agent", 50);

        assertEquals(0, deleted.getStatus());
        assertEquals(List.of("active"), visible.stream().map(AiSession::getSessionId).toList());
        verify(sessionDao).softDelete("sess-3");
    }

    @Test
    void detailIncludesLatestFeedbackSignalsInOverview() {
        IChatMessageDao messageDao = mock(IChatMessageDao.class);
        IMemorySummaryDao summaryDao = mock(IMemorySummaryDao.class);
        ReflectionTestUtils.setField(service, "messageDao", messageDao);
        ReflectionTestUtils.setField(service, "summaryDao", summaryDao);

        when(sessionDao.queryByAgentAndSession("cs", "sess-001")).thenReturn(AiSession.builder()
                .agentId("cs").sessionId("sess-001").status(1).title("补货反馈").build());
        when(messageDao.queryBySessionId("sess-001")).thenReturn(List.of(
                ChatMessage.builder().agentId("cs").sessionId("sess-001").role("user").content("DDR5 缺货").build(),
                ChatMessage.builder().agentId("cs").sessionId("sess-001").role("assistant").content("我已记录反馈").build(),
                ChatMessage.builder().agentId("cs").sessionId("sess-001").role("tool").content("无").build(),
                ChatMessage.builder().agentId("other").sessionId("sess-001").role("assistant").content("ignored").build()
        ));
        when(feedbackDao.queryBySession("cs", "sess-001", 20)).thenReturn(List.of(
                AiFeedback.builder().id(100L).agentId("cs").sessionId("sess-001")
                        .status("PROMOTED").sourceType("AI_INFERRED").matchedCaseId("case-feedback-100")
                        .message("DDR5 内存缺货，希望补货").createdAt(LocalDateTime.now()).build(),
                AiFeedback.builder().id(99L).agentId("cs").sessionId("sess-001")
                        .status("OPEN").sourceType("USER").message("较早反馈").createdAt(LocalDateTime.now().minusMinutes(1)).build()
        ));
        when(caseDao.queryBySession("cs", "sess-001", 10)).thenReturn(List.of(
                AiCase.builder().caseId("case-feedback-100").agentId("cs").title("DDR5 内存缺货").status("CANDIDATE").build()
        ));
        when(summaryDao.queryLatest("sess-001")).thenReturn(MemorySummary.builder()
                .agentId("cs").sessionId("sess-001").summary("用户反馈 DDR5 内存缺货，已经升级候选 Case").build());
        when(executionDao.queryLatestBySession("cs", "sess-001")).thenReturn(AgentExecution.builder()
                .executionId("exec-001").agentId("cs").sessionId("sess-001")
                .routeType("feedback").status("COMPLETED").modelId("deepseek-v4-flash")
                .updatedAt(LocalDateTime.now()).build());

        Map<String, Object> detail = service.detail("cs", "sess-001");
        Map<String, Object> overview = cast(detail.get("overview"));

        assertEquals(3, ((List<?>) detail.get("messages")).size());
        assertEquals(2, overview.get("feedbackCount"));
        assertEquals(1L, overview.get("promotedFeedbackCount"));
        assertEquals("PROMOTED", overview.get("latestFeedbackStatus"));
        assertEquals("AI_INFERRED", overview.get("latestFeedbackSourceType"));
        assertEquals("case-feedback-100", overview.get("latestMatchedCaseId"));
        assertTrue(Boolean.TRUE.equals(overview.get("hasMemorySummary")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
