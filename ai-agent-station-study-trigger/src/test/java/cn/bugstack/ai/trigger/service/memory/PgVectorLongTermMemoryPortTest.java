package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PgVectorLongTermMemoryPortTest {

    @Test
    void indexesPublishedCardWithLocatorMetadataOnly() {
        VectorStore vectorStore = mock(VectorStore.class);
        PgVectorLongTermMemoryPort port = new PgVectorLongTermMemoryPort(vectorStore);

        port.index(new LongTermMemoryPort.PublishedMemoryDocument(
                "inventory", "mem-1", 2, "RESOLVED_CASE", "库存不一致",
                "下单后库存未扣减", "库存不一致 下单后库存未扣减", "case-1", "PUBLISHED"));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore).accept(captor.capture());
        Document document = (Document) captor.getValue().get(0);
        assertEquals("inventory:mem-1:v2", document.getId());
        assertEquals("mem-1", document.getMetadata().get("memory_id"));
        assertEquals(2, document.getMetadata().get("version"));
        assertEquals("PUBLISHED", document.getMetadata().get("status"));
        assertEquals(6, document.getMetadata().size());
    }

    @Test
    void storesMemoryFactAsAgentScopedVectorDocument() {
        VectorStore vectorStore = mock(VectorStore.class);
        PgVectorLongTermMemoryPort port = new PgVectorLongTermMemoryPort(vectorStore);

        port.store(new LongTermMemoryPort.MemoryFact(
                "auto_agent", "auto_agent", "SESSION_SUMMARY",
                "用户正在设计企业级长期记忆。", "session-1", "system-derived"));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore).accept(captor.capture());
        Document document = (Document) captor.getValue().get(0);
        assertEquals("用户正在设计企业级长期记忆。", document.getText());
        assertEquals("auto_agent", document.getMetadata().get("agent_id"));
        assertEquals("auto_agent", document.getMetadata().get("subject_id"));
        assertEquals("SESSION_SUMMARY", document.getMetadata().get("kind"));
        assertEquals("session-1", document.getMetadata().get("source_session_id"));
    }

    @Test
    void retrievesAgentScopedMemoryFactsFromVectorSearch() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.<SearchRequest>any())).thenReturn(List.of(
                Document.builder()
                        .text("长期记忆：用户偏好中文企业级后台。")
                        .metadata(Map.of("kind", "SESSION_SUMMARY", "source_session_id", "session-2"))
                        .build()));
        PgVectorLongTermMemoryPort port = new PgVectorLongTermMemoryPort(vectorStore);

        List<LongTermMemoryPort.MemoryFact> facts = port.retrieve("auto_agent", "auto_agent", "记忆偏好", 3);

        assertEquals(1, facts.size());
        assertEquals("长期记忆：用户偏好中文企业级后台。", facts.get(0).content());
        assertEquals("SESSION_SUMMARY", facts.get(0).kind());
        verify(vectorStore).similaritySearch(org.mockito.ArgumentMatchers.<SearchRequest>argThat(request ->
                request.getTopK() == 3 && request.getFilterExpression() != null));
    }
}
