package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryExtractionCursorDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.trigger.service.analysis.AgentEvaluationContextBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationMemoryCandidateExtractorTest {

    private final IChatMessageDao messageDao = mock(IChatMessageDao.class);
    private final IAgentMemoryExtractionCursorDao cursorDao = mock(IAgentMemoryExtractionCursorDao.class);
    private final AgentMemoryCandidateService candidateService = mock(AgentMemoryCandidateService.class);
    private final MemoryCandidateModelClient modelClient = mock(MemoryCandidateModelClient.class);
    private final AgentEvaluationContextBuilder contextBuilder = mock(AgentEvaluationContextBuilder.class);
    private final ConversationMemoryCandidateExtractor extractor = new ConversationMemoryCandidateExtractor(
            messageDao, cursorDao, candidateService, modelClient, new MemoryQueryAdmissionPolicy(), contextBuilder);

    @Test
    void singleCharacterConversationDoesNotCreateCandidateOrAdvanceCursor() {
        when(messageDao.queryBySessionId("s1")).thenReturn(List.of(message(1, "user", "1")));

        assertEquals(ConversationMemoryCandidateExtractor.Status.SKIPPED_LOW_INFORMATION,
                extractor.extractIfEligible("inventory", "s1", "m1").status());
        verifyNoInteractions(candidateService, modelClient);
        verify(cursorDao, never()).advance(any(), any(), anyLong(), anyLong());
    }

    @Test
    void failedExtractionDoesNotAdvanceCursor() {
        when(messageDao.queryBySessionId("s2")).thenReturn(List.of(
                message(1, "user", "DDR5 内存持续缺货，多个用户无法下单，希望尽快补货")));
        when(modelClient.extract(any())).thenThrow(new RuntimeException("429"));

        assertThrows(RuntimeException.class, () -> extractor.extractIfEligible("inventory", "s2", "m1"));
        verify(cursorDao, never()).advance(any(), any(), anyLong(), anyLong());
        verify(cursorDao).markFailure(eq("inventory"), eq("s2"), contains("429"));
    }

    @Test
    void successfulCandidateCommitAdvancesCursorOnce() {
        when(messageDao.queryBySessionId("s3")).thenReturn(List.of(
                message(18, "user", "DDR5 内存持续缺货，多个用户无法下单，希望尽快补货")));
        when(modelClient.extract(any())).thenReturn(new MemoryCandidateModelClient.Extraction(
                true, "BUSINESS_RULE", "inventory:ddr5-replenishment", "DDR5 补货规则",
                "DDR5 持续缺货影响下单时需要补货", "{}", 88,
                List.of(new MemoryCandidateModelClient.Evidence(18L,
                        "DDR5 内存持续缺货，多个用户无法下单，希望尽快补货"))));
        when(candidateService.submitCandidate(any())).thenReturn("candidate-1");
        when(cursorDao.advance("inventory", "s3", 0L, 18L)).thenReturn(1);

        var result = extractor.extractIfEligible("inventory", "s3", "m1");

        assertEquals(ConversationMemoryCandidateExtractor.Status.CANDIDATE_CREATED, result.status());
        verify(candidateService).submitCandidate(any());
        verify(cursorDao).advance("inventory", "s3", 0L, 18L);
    }

    private ChatMessage message(long id, String role, String content) {
        return ChatMessage.builder().id(id).agentId("inventory").sessionId("s").role(role).content(content).build();
    }
}
