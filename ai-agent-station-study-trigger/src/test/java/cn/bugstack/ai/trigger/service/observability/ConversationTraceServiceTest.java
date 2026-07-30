package cn.bugstack.ai.trigger.service.observability;

import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationTraceServiceTest {

    private IChatMessageDao chatMessageDao;
    private IAiLlmLogDao llmLogDao;
    private IAiFeedbackDao feedbackDao;
    private IAiCaseDao caseDao;
    private ConversationTraceService service;

    @BeforeEach
    void setUp() {
        chatMessageDao = mock(IChatMessageDao.class);
        llmLogDao = mock(IAiLlmLogDao.class);
        feedbackDao = mock(IAiFeedbackDao.class);
        caseDao = mock(IAiCaseDao.class);
        service = new ConversationTraceService();
        ReflectionTestUtils.setField(service, "chatMessageDao", chatMessageDao);
        ReflectionTestUtils.setField(service, "llmLogDao", llmLogDao);
        ReflectionTestUtils.setField(service, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(service, "caseDao", caseDao);
    }

    @Test
    void traceSortsMessagesLlmFeedbackAndCaseByTime() {
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 30, 10, 0);
        LocalDateTime t2 = t1.plusSeconds(1);
        LocalDateTime t3 = t1.plusSeconds(2);
        LocalDateTime t4 = t1.plusSeconds(3);
        LocalDateTime t5 = t1.plusSeconds(4);
        when(chatMessageDao.queryBySessionId("sess-1")).thenReturn(List.of(
                ChatMessage.builder().id(1L).agentId("cs").sessionId("sess-1").role("user").content("库存不一致").turn(1).step(0).createdAt(t1).build(),
                ChatMessage.builder().id(2L).agentId("cs").sessionId("sess-1").role("tool").toolName("read_file").toolArguments("{\"path\":\".ma/skills/a/SKILL.md\"}").content("读取成功").turn(1).step(1).createdAt(t3).build(),
                ChatMessage.builder().id(3L).agentId("other").sessionId("sess-1").role("user").content("其它Agent消息").createdAt(t2).build()
        ));
        when(llmLogDao.queryBySessionId("sess-1", 100)).thenReturn(List.of(
                AiLlmLog.builder().id(10L).agentId("cs").sessionId("sess-1").modelName("deepseek").mode("react").status("success").durationMs(120).totalTokens(321).createdAt(t2).build()
        ));
        when(feedbackDao.queryBySession("cs", "sess-1", 50)).thenReturn(List.of(
                AiFeedback.builder().id(20L).agentId("cs").sessionId("sess-1").message("库存不一致").status("VALID").createdAt(t4).build()
        ));
        when(caseDao.queryBySession("cs", "sess-1", 50)).thenReturn(List.of(
                AiCase.builder().caseId("case-1").agentId("cs").title("库存问题").status("CANDIDATE").createdAt(t5).build()
        ));

        ConversationTraceService.ConversationTrace trace = service.trace("cs", "sess-1");

        assertEquals("cs", trace.agentId());
        assertEquals("sess-1", trace.sessionId());
        assertEquals(5, trace.timeline().size());
        assertEquals("USER_MESSAGE", trace.timeline().get(0).type());
        assertEquals("LLM_CALL", trace.timeline().get(1).type());
        assertEquals("TOOL_CALL", trace.timeline().get(2).type());
        assertEquals("FEEDBACK", trace.timeline().get(3).type());
        assertEquals("CASE", trace.timeline().get(4).type());
        assertEquals(1, trace.summary().toolCalls());
        assertEquals(1, trace.summary().feedbackCount());
        assertEquals(1, trace.summary().caseCount());
    }

    @Test
    void traceRejectsSessionThatHasNoMessageOrLogForAgent() {
        when(chatMessageDao.queryBySessionId("sess-404")).thenReturn(List.of(
                ChatMessage.builder().id(1L).agentId("ops").sessionId("sess-404").role("user").content("ops").build()
        ));
        when(llmLogDao.queryBySessionId("sess-404", 100)).thenReturn(List.of());
        when(feedbackDao.queryBySession("cs", "sess-404", 50)).thenReturn(List.of());
        when(caseDao.queryBySession("cs", "sess-404", 50)).thenReturn(List.of());

        ConversationTraceService.ConversationTrace trace = service.trace("cs", "sess-404");

        assertTrue(trace.timeline().isEmpty());
        assertEquals(0, trace.summary().messageCount());
    }
}
