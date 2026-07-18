package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.memory.long-term", name = "provider", havingValue = "pgvector")
public class PgVectorLongTermMemoryPort implements LongTermMemoryPort {

    private final VectorStore vectorStore;

    public PgVectorLongTermMemoryPort(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void store(MemoryFact fact) {
        if (fact == null || fact.content() == null || fact.content().isBlank()) return;
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("memory_type", "long_term");
            metadata.put("agent_id", safe(fact.agentId()));
            metadata.put("subject_id", safe(fact.subjectId()));
            metadata.put("kind", safe(fact.kind()));
            metadata.put("source_session_id", safe(fact.sourceSessionId()));
            metadata.put("consent_reference", safe(fact.consentReference()));
            vectorStore.accept(List.of(Document.builder()
                    .text(fact.content())
                    .metadata(metadata)
                    .build()));
        } catch (Exception exception) {
            log.warn("PgVector long-term memory store failed; continuing without memory write: {}", exception.getMessage());
        }
    }

    @Override
    public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        try {
            int topK = Math.min(Math.max(limit, 1), 10);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(filter(agentId, subjectId))
                    .build();
            List<Document> documents = vectorStore.similaritySearch(request);
            if (documents == null || documents.isEmpty()) return List.of();
            return documents.stream()
                    .map(document -> new MemoryFact(
                            safe(agentId),
                            safe(subjectId),
                            metadata(document, "kind", "MEMORY"),
                            document.getText(),
                            metadata(document, "source_session_id", ""),
                            "pgvector-bge-m3"))
                    .filter(memory -> memory.content() != null && !memory.content().isBlank())
                    .toList();
        } catch (Exception exception) {
            log.warn("PgVector long-term memory retrieval failed; continuing without memory recall: {}", exception.getMessage());
            return List.of();
        }
    }

    private static String filter(String agentId, String subjectId) {
        return "memory_type == 'long_term' && agent_id == '" + escape(agentId)
                + "' && subject_id == '" + escape(subjectId) + "'";
    }

    private static String metadata(Document document, String key, String fallback) {
        Object value = document.getMetadata().get(key);
        return value == null ? fallback : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        return safe(value).replace("'", "\\'");
    }
}
