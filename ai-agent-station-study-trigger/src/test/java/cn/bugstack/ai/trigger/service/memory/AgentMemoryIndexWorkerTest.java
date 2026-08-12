package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryIndexOutboxDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryIndexOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryIndexWorkerTest {

    @Test
    void failedEmbeddingLeavesOutboxRetryable() {
        IAgentMemoryIndexOutboxDao outboxDao = mock(IAgentMemoryIndexOutboxDao.class);
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        LongTermMemoryPort indexPort = mock(LongTermMemoryPort.class);
        when(outboxDao.queryClaimable()).thenReturn(event(1));
        when(outboxDao.claim(1L)).thenReturn(1);
        doThrow(new RuntimeException("embedding unavailable")).when(indexPort).index(any());

        when(cardDao.queryPublishedVersion("inventory", "mem-1", 2)).thenReturn(publishedCard());
        new AgentMemoryIndexWorker(outboxDao, cardDao, indexPort, 5).processNext();

        verify(outboxDao).markRetry(eq("event-1"), contains("embedding unavailable"), any(LocalDateTime.class));
        verify(outboxDao, never()).markDone("event-1");
    }

    @Test
    void indexedDocumentContainsOnlyPublishedCardLocator() {
        IAgentMemoryIndexOutboxDao outboxDao = mock(IAgentMemoryIndexOutboxDao.class);
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        LongTermMemoryPort indexPort = mock(LongTermMemoryPort.class);
        when(outboxDao.queryClaimable()).thenReturn(event(0));
        when(outboxDao.claim(1L)).thenReturn(1);

        when(cardDao.queryPublishedVersion("inventory", "mem-1", 2)).thenReturn(publishedCard());
        new AgentMemoryIndexWorker(outboxDao, cardDao, indexPort, 5).processNext();

        verify(indexPort).index(argThat(document -> document.agentId().equals("inventory")
                && document.memoryId().equals("mem-1") && document.version() == 2
                && document.searchText().contains("库存不一致")));
        verify(outboxDao).markDone("event-1");
    }

    @Test
    void tooManyFailuresMoveEventToDeadLetterState() {
        IAgentMemoryIndexOutboxDao outboxDao = mock(IAgentMemoryIndexOutboxDao.class);
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        LongTermMemoryPort indexPort = mock(LongTermMemoryPort.class);
        when(outboxDao.queryClaimable()).thenReturn(event(4));
        when(outboxDao.claim(1L)).thenReturn(1);
        doThrow(new RuntimeException("index unavailable")).when(indexPort).index(any());

        when(cardDao.queryPublishedVersion("inventory", "mem-1", 2)).thenReturn(publishedCard());
        new AgentMemoryIndexWorker(outboxDao, cardDao, indexPort, 5).processNext();

        verify(outboxDao).markFailed(eq("event-1"), contains("index unavailable"));
        verify(outboxDao, never()).markRetry(anyString(), anyString(), any());
    }

    private AgentMemoryIndexOutbox event(int attempts) {
        return AgentMemoryIndexOutbox.builder().id(1L).eventId("event-1").agentId("inventory")
                .memoryId("mem-1").memoryVersion(2).eventType("UPSERT")
                .payloadJson("{\"memoryType\":\"RESOLVED_CASE\",\"title\":\"库存不一致\","
                        + "\"description\":\"下单后库存未扣减\",\"contentJson\":\"{}\","
                        + "\"sourceCaseId\":\"case-1\"}")
                .status("PENDING").attempts(attempts).build();
    }

    private AgentMemoryCard publishedCard() {
        return AgentMemoryCard.builder().agentId("inventory").memoryId("mem-1").version(2).status("PUBLISHED").build();
    }
}
