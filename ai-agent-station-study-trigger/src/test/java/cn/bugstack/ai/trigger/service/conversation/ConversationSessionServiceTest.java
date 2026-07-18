package cn.bugstack.ai.trigger.service.conversation;

import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ConversationSessionServiceTest {

    private IAiAgentDao agentDao;
    private IAiSessionDao sessionDao;
    private ConversationSessionService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(IAiAgentDao.class);
        sessionDao = mock(IAiSessionDao.class);
        service = new ConversationSessionService();
        ReflectionTestUtils.setField(service, "agentDao", agentDao);
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
}
