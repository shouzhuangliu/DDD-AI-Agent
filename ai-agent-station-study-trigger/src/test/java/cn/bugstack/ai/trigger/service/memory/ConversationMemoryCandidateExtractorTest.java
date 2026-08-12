package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.infrastructure.dao.IAgentMemoryExtractionCursorDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.trigger.service.analysis.AgentEvaluationContextBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationMemoryCandidateExtractorTest {

    private final IChatMessageDao messageDao = mock(IChatMessageDao.class);
    private final IAgentMemoryExtractionCursorDao cursorDao = mock(IAgentMemoryExtractionCursorDao.class);
    private final AgentMemoryLifecycleService lifecycleService = mock(AgentMemoryLifecycleService.class);
    private final MemoryCandidateModelClient modelClient = mock(MemoryCandidateModelClient.class);
    private final AgentEvaluationContextBuilder contextBuilder = mock(AgentEvaluationContextBuilder.class);
    private final ConversationMemoryCandidateExtractor extractor = new ConversationMemoryCandidateExtractor(
            messageDao, cursorDao, lifecycleService, modelClient, new MemoryQueryAdmissionPolicy(), contextBuilder);

    @Test
    void singleCharacterConversationDoesNotWriteMemoryOrAdvanceCursor() {
        when(messageDao.queryBySessionId("s1")).thenReturn(List.of(message(1, "user", "1")));

        assertEquals(ConversationMemoryCandidateExtractor.Status.SKIPPED_LOW_INFORMATION,
                extractor.extractIfEligible("inventory", "s1", "m1").status());
        verifyNoInteractions(lifecycleService, modelClient);
        verify(cursorDao, never()).advance(any(), any(), anyLong(), anyLong());
    }

    @Test
    void failedExtractionDoesNotAdvanceCursor() {
        when(messageDao.queryBySessionId("s2")).thenReturn(List.of(
                message(1, "user", "DDR5 memory remains out of stock and customers cannot order")));
        when(modelClient.extract(any())).thenThrow(new RuntimeException("429"));

        assertThrows(RuntimeException.class, () -> extractor.extractIfEligible("inventory", "s2", "m1"));
        verify(cursorDao, never()).advance(any(), any(), anyLong(), anyLong());
        verify(cursorDao).markFailure(eq("inventory"), eq("s2"), contains("429"));
    }

    @Test
    void eligibleConversationCreatesBusinessMemoryAndAdvancesCursorOnce() {
        when(messageDao.queryBySessionId("s3")).thenReturn(List.of(
                message(18, "user", "DDR5 memory remains out of stock and customers cannot order")));
        when(modelClient.extract(any())).thenReturn(extraction("CREATE", ""));
        when(lifecycleService.upsert(any())).thenReturn(new AgentMemoryLifecyclePort.Result("memory-1", 1, "CREATE"));
        when(cursorDao.advance("inventory", "s3", 0L, 18L)).thenReturn(1);

        var result = extractor.extractIfEligible("inventory", "s3", "m1");

        assertEquals(ConversationMemoryCandidateExtractor.Status.MEMORY_CREATED, result.status());
        verify(lifecycleService).upsert(any());
        verify(cursorDao).advance("inventory", "s3", 0L, 18L);
    }

    @Test
    void updateExtractionUpdatesExistingMemoryInsteadOfCreatingCandidate() {
        when(messageDao.queryBySessionId("s4")).thenReturn(List.of(
                message(24, "user", "Inventory variance above two percent requires priority investigation")));
        when(modelClient.extract(any())).thenReturn(extraction("UPDATE", "memory-1"));
        when(lifecycleService.upsert(any())).thenReturn(new AgentMemoryLifecyclePort.Result("memory-1", 2, "UPDATE"));
        when(cursorDao.advance("inventory", "s4", 0L, 24L)).thenReturn(1);

        var result = extractor.extractIfEligible("inventory", "s4", "m1");

        assertEquals(ConversationMemoryCandidateExtractor.Status.MEMORY_UPDATED, result.status());
        verify(lifecycleService).upsert(any());
    }

    private MemoryCandidateModelClient.Extraction extraction(String operation, String targetMemoryId) {
        return new MemoryCandidateModelClient.Extraction(operation, targetMemoryId, true,
                "BUSINESS_RULE", "inventory:stock-threshold", "Inventory variance threshold",
                "Inventory variance over two percent requires priority investigation", "{}", 88,
                List.of(new MemoryCandidateModelClient.Evidence(18L, "Inventory variance threshold")));
    }

    private ChatMessage message(long id, String role, String content) {
        return ChatMessage.builder().id(id).agentId("inventory").sessionId("s").role(role).content(content).build();
    }
}
