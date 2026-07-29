package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mem0 OSS REST adapter. It deliberately fails open so memory never blocks a response. */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.memory.long-term", name = "provider", havingValue = "mem0")
public class Mem0LongTermMemoryPort implements LongTermMemoryPort {

    private final RestClient client;

    public Mem0LongTermMemoryPort(@Value("${agent.memory.mem0.base-url:http://127.0.0.1:18888}") String baseUrl,
                                  @Value("${agent.memory.mem0.api-key:}") String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) builder.defaultHeader("X-API-Key", apiKey);
        this.client = builder.build();
    }

    @Override
    public void store(MemoryFact fact) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messages", List.of(Map.of("role", "user", "content", fact.content())));
            payload.put("agent_id", fact.agentId());
            payload.put("user_id", fact.subjectId());
            payload.put("metadata", Map.of("kind", fact.kind(), "source_session_id", fact.sourceSessionId(),
                    "consent_reference", fact.consentReference(), "source_case_id", fact.sourceCaseId(),
                    "profile_version", fact.profileVersion()));
            client.post().uri("/memories").contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
        } catch (Exception exception) {
            log.warn("Mem0 store failed; continuing without long-term memory: {}", exception.getMessage());
        }
    }

    @Override
    public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        try {
            String body = client.post().uri("/search").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "agent_id", agentId, "user_id", subjectId, "limit", Math.min(Math.max(limit, 1), 10)))
                    .retrieve().body(String.class);
            JSONObject root = JSON.parseObject(body);
            JSONArray results = root == null ? null : root.getJSONArray("results");
            if (results == null) return List.of();
            return results.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                    .map(item -> new MemoryFact(agentId, subjectId,
                            item.getJSONObject("metadata") == null ? "MEM0" : item.getJSONObject("metadata").getString("kind"),
                            item.getString("memory"),
                            item.getJSONObject("metadata") == null ? "" : item.getJSONObject("metadata").getString("source_session_id"),
                            "mem0",
                            item.getJSONObject("metadata") == null ? "" : item.getJSONObject("metadata").getString("source_case_id"),
                            profileVersion(item)))
                    .filter(item -> item.content() != null && !item.content().isBlank()).toList();
        } catch (Exception exception) {
            log.warn("Mem0 retrieval failed; continuing without long-term memory: {}", exception.getMessage());
            return List.of();
        }
    }

    private int profileVersion(JSONObject item) {
        if (item.getJSONObject("metadata") == null) return 0;
        Integer value = item.getJSONObject("metadata").getInteger("profile_version");
        return value == null ? 0 : value;
    }
}
