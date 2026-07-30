package cn.bugstack.ai.trigger.service.observability;

import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IAgentExecutionDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemoryToolResult;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTask;
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
    private IAgentExecutionDao agentExecutionDao;
    private IMemoryToolResultDao memoryToolResultDao;
    private ISubagentTaskDao subagentTaskDao;
    private ConversationTraceService service;

    @BeforeEach
    void setUp() {
        chatMessageDao = mock(IChatMessageDao.class);
        llmLogDao = mock(IAiLlmLogDao.class);
        feedbackDao = mock(IAiFeedbackDao.class);
        caseDao = mock(IAiCaseDao.class);
        agentExecutionDao = mock(IAgentExecutionDao.class);
        memoryToolResultDao = mock(IMemoryToolResultDao.class);
        subagentTaskDao = mock(ISubagentTaskDao.class);
        service = new ConversationTraceService();
        ReflectionTestUtils.setField(service, "chatMessageDao", chatMessageDao);
        ReflectionTestUtils.setField(service, "llmLogDao", llmLogDao);
        ReflectionTestUtils.setField(service, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(service, "caseDao", caseDao);
        ReflectionTestUtils.setField(service, "agentExecutionDao", agentExecutionDao);
        ReflectionTestUtils.setField(service, "memoryToolResultDao", memoryToolResultDao);
        ReflectionTestUtils.setField(service, "subagentTaskDao", subagentTaskDao);
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
    void traceAddsExecutionTodoToolResultAndSubagentEvents() {
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 30, 11, 0);
        when(chatMessageDao.queryBySessionId("sess-2")).thenReturn(List.of());
        when(llmLogDao.queryBySessionId("sess-2", 100)).thenReturn(List.of());
        when(feedbackDao.queryBySession("cs", "sess-2", 50)).thenReturn(List.of());
        when(caseDao.queryBySession("cs", "sess-2", 50)).thenReturn(List.of());
        when(agentExecutionDao.queryLatestBySession("cs", "sess-2")).thenReturn(AgentExecution.builder()
                .executionId("exec-1")
                .agentId("cs")
                .sessionId("sess-2")
                .routeType("react")
                .status("RUNNING")
                .stateJson("{\"todos\":[{\"todoId\":\"todo-1\",\"content\":\"读取业务Skill\",\"status\":\"IN_PROGRESS\"}]}")
                .startedAt(t1)
                .updatedAt(t1.plusSeconds(1))
                .build());
        when(memoryToolResultDao.queryBySession("sess-2", 50)).thenReturn(List.of(
                MemoryToolResult.builder()
                        .agentId("cs")
                        .sessionId("sess-2")
                        .messageId(12L)
                        .toolName("read_file")
                        .keyParametersJson("{\"path\":\".ma/skills/demo/SKILL.md\"}")
                        .conclusion("读取到 Skill 手册")
                        .createdAt(t1.plusSeconds(2))
                        .build(),
                MemoryToolResult.builder()
                        .agentId("cs")
                        .sessionId("sess-2")
                        .toolName("call_mcp_tool")
                        .errorSummary("MCP 连接超时")
                        .createdAt(t1.plusSeconds(3))
                        .build()
        ));
        when(subagentTaskDao.queryByExecutionId("exec-1", 50)).thenReturn(List.of(
                SubagentTask.builder()
                        .taskId("sub-1")
                        .executionId("exec-1")
                        .agentId("cs")
                        .description("并行排查库存服务")
                        .status("FAILED")
                        .errorMessage("子任务超时")
                        .startedAt(t1.plusSeconds(4))
                        .build()
        ));

        ConversationTraceService.ConversationTrace trace = service.trace("cs", "sess-2");

        assertEquals(List.of("ROUTE", "TODO", "TOOL_RESULT", "TOOL_RESULT", "SUBAGENT"),
                trace.timeline().stream().map(ConversationTraceService.TimelineEvent::type).toList());
        assertEquals("SKILL", trace.timeline().get(2).toolSource());
        assertEquals("FAILED", trace.timeline().get(3).status());
        assertTrue(trace.timeline().get(3).errorMessage().contains("MCP 连接超时"));
        assertEquals(2, trace.summary().toolCalls());
        assertTrue(trace.summary().hasFailure());
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
