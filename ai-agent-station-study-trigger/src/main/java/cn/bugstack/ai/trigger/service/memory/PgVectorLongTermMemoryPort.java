package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public PgVectorLongTermMemoryPort(@Qualifier("pgVectorStore") VectorStore vectorStore) {
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
            metadata.put("source_case_id", safe(fact.sourceCaseId()));
            metadata.put("profile_version", fact.profileVersion());
            vectorStore.accept(List.of(Document.builder()
                    .text(fact.content())
                    .metadata(metadata)
                    .build()));
        } catch (Exception exception) {
            log.warn("PgVector long-term memory store failed; continuing without memory write: {}",
                    exception.getMessage(), exception);
        }
    }

    @Override
    public void index(PublishedMemoryDocument memory) {
        if (memory == null || memory.searchText() == null || memory.searchText().isBlank()) {
            throw new IllegalArgumentException("published memory search text is required");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memory_type", safe(memory.memoryType()));
        metadata.put("agent_id", safe(memory.agentId()));
        metadata.put("memory_id", safe(memory.memoryId()));
        metadata.put("version", memory.version());
        metadata.put("status", safe(memory.status()));
        metadata.put("source_case_id", safe(memory.sourceCaseId()));
        vectorStore.accept(List.of(Document.builder()
                .id(indexDocumentId(memory.agentId(), memory.memoryId(), memory.version()))
                .text(memory.searchText())
                .metadata(metadata)
                .build()));
    }

    @Override
    public void delete(String agentId, String memoryId, int version) {
        vectorStore.delete(List.of(indexDocumentId(agentId, memoryId, version)));
    }

    @Override
    public List<MemoryIndexReference> searchIndex(String agentId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        SearchRequest request = SearchRequest.builder().query(query).topK(Math.min(Math.max(limit, 1), 10))
                .filterExpression("agent_id == '" + escape(agentId) + "' && status == 'PUBLISHED'")
                .build();
        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents == null) return List.of();
        return documents.stream()
                .filter(document -> !metadata(document, "memory_id", "").isBlank())
                .map(document -> new MemoryIndexReference(agentId,
                        metadata(document, "memory_id", ""),
                        integerMetadata(document, "version"), document.getScore() == null ? 0D : document.getScore()))
                .toList();
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
                            "pgvector-bge-m3",
                            metadata(document, "source_case_id", ""),
                            parseVersion(document)))
                    .filter(memory -> memory.content() != null && !memory.content().isBlank())
                    .toList();
        } catch (Exception exception) {
            log.warn("PgVector long-term memory retrieval failed; continuing without memory recall: {}",
                    exception.getMessage(), exception);
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

    private static int parseVersion(Document document) {
        try {
            return Integer.parseInt(metadata(document, "profile_version", "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String escape(String value) {
        return safe(value).replace("'", "\\'");
    }

    private static int integerMetadata(Document document, String key) {
        try { return Integer.parseInt(metadata(document, key, "1")); }
        catch (NumberFormatException ignored) { return 1; }
    }

    private static String indexDocumentId(String agentId, String memoryId, int version) {
        return safe(agentId) + ":" + safe(memoryId) + ":v" + version;
    }
}
