package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryProfileDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LongTermMemoryRecallService {

    private static final List<String> PROFILE_SECTIONS = List.of(
            "failure_patterns", "business_rules", "resolution_patterns", "capabilities", "preferences");

    private final LongTermMemoryPort longTermMemoryPort;
    private final IMemorySummaryDao memorySummaryDao;
    private final IAgentMemoryProfileDao profileDao;
    private final MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;

    public LongTermMemoryRecallService(LongTermMemoryPort longTermMemoryPort,
                                       IMemorySummaryDao memorySummaryDao,
                                       IAgentMemoryProfileDao profileDao,
                                       MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy) {
        this.longTermMemoryPort = longTermMemoryPort;
        this.memorySummaryDao = memorySummaryDao;
        this.profileDao = profileDao;
        this.memoryQueryAdmissionPolicy = memoryQueryAdmissionPolicy;
    }

    public List<MemoryRecallItem> recall(String agentId, String query, int limit) {
        String safeAgentId = safe(agentId);
        String safeQuery = safe(query);
        int safeLimit = Math.max(1, Math.min(20, limit));
        if (safeAgentId.isBlank() || safeQuery.isBlank()) return List.of();
        if (!memoryQueryAdmissionPolicy.shouldRecall(safeQuery)) return List.of();

        Map<String, MemoryRecallItem> merged = new LinkedHashMap<>();
        longTermMemoryPort.retrieve(safeAgentId, safeAgentId, safeQuery, safeLimit).stream()
                .filter(memory -> safeAgentId.equals(safe(memory.agentId())))
                .filter(memory -> !safe(memory.content()).isBlank())
                .map(memory -> new MemoryRecallItem(
                        safeAgentId,
                        "LONG_TERM_VECTOR",
                        firstNonBlank(memory.sourceCaseId(), memory.sourceSessionId(), memory.consentReference()),
                        safe(memory.kind()),
                        compact(memory.content()),
                        score(memory.content(), safeQuery, 88d),
                        memory.sourceSessionId(),
                        memory.sourceCaseId(),
                        memory.profileVersion(),
                        null,
                        Map.of("subjectId", safe(memory.subjectId()), "consentReference", safe(memory.consentReference()))
                ))
                .forEach(item -> merged.putIfAbsent(key(item), item));

        memorySummaryDao.queryByAgent(safeAgentId, safeLimit).stream()
                .filter(summary -> safeAgentId.equals(safe(summary.getAgentId())))
                .filter(summary -> !"SUPERSEDED".equalsIgnoreCase(safe(summary.getStatus())))
                .filter(summary -> !safe(summary.getSummary()).isBlank())
                .map(summary -> new MemoryRecallItem(
                        safeAgentId,
                        "SESSION_SUMMARY",
                        safe(summary.getSessionId()),
                        "短期折叠摘要",
                        compact(summary.getSummary()),
                        score(summary.getSummary(), safeQuery, 62d),
                        safe(summary.getSessionId()),
                        "",
                        valueOr(summary.getVersion(), 0),
                        summary.getCreatedAt(),
                        Map.of("modelId", safe(summary.getModelId()), "tokenCount", valueOr(summary.getTokenCount(), 0))
                ))
                .filter(item -> item.score() > 0)
                .forEach(item -> merged.putIfAbsent(key(item), item));

        profileEntries(profileDao.queryLatest(safeAgentId), safeQuery).forEach(item -> merged.putIfAbsent(key(item), item));

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MemoryRecallItem::score).reversed())
                .limit(safeLimit)
                .toList();
    }

    private List<MemoryRecallItem> profileEntries(AgentMemoryProfile profile, String query) {
        if (profile == null || safe(profile.getAgentId()).isBlank() || safe(profile.getProfileJson()).isBlank()) return List.of();
        JSONObject root;
        try {
            root = JSON.parseObject(profile.getProfileJson());
        } catch (RuntimeException ignored) {
            return List.of();
        }
        List<MemoryRecallItem> items = new ArrayList<>();
        for (String section : PROFILE_SECTIONS) {
            JSONArray values = root == null ? null : root.getJSONArray(section);
            if (values == null) continue;
            for (Object value : values) {
                if (!(value instanceof JSONObject object)) continue;
                if (!"RESOLVED".equalsIgnoreCase(safe(object.getString("status")))) continue;
                String text = firstNonBlank(object.getString("text"), object.getString("summary"), object.getString("memory"));
                if (text.isBlank()) continue;
                double itemScore = score(text, query, 74d);
                if (itemScore <= 0) continue;
                items.add(new MemoryRecallItem(
                        safe(profile.getAgentId()),
                        "AGENT_PROFILE",
                        safe(object.getString("caseId")),
                        section,
                        compact(text),
                        itemScore,
                        "",
                        safe(object.getString("caseId")),
                        valueOr(profile.getVersion(), 0),
                        profile.getUpdatedAt(),
                        Map.of("section", section, "reason", safe(object.getString("reason")))
                ));
            }
        }
        return items;
    }

    private double score(String content, String query, double base) {
        String text = safe(content).toLowerCase();
        String q = safe(query).toLowerCase();
        if (text.isBlank() || q.isBlank()) return 0d;
        if (text.contains(q)) return Math.min(100d, base + 20d);
        double score = 0d;
        for (String token : q.split("[\\s,，。；;]+")) {
            if (!token.isBlank() && text.contains(token)) score += 18d;
        }
        return score == 0d ? 0d : Math.min(100d, base + score);
    }

    private String key(MemoryRecallItem item) {
        return item.sourceType() + ":" + item.sourceId() + ":" + item.summary();
    }

    private String compact(String value) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!safe(value).isBlank()) return safe(value);
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }

    public record MemoryRecallItem(String agentId, String sourceType, String sourceId, String kind,
                                   String summary, double score, String sourceSessionId, String sourceCaseId,
                                   int profileVersion, LocalDateTime createdAt, Map<String, Object> metadata) {
    }
}
