package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AgentMemoryProfileService {

    private static final List<String> SECTIONS = List.of(
            "capabilities", "failure_patterns", "business_rules", "resolution_patterns", "preferences");

    private final IAgentMemoryProfileDao profileDao;
    private final LongTermMemoryPort longTermMemoryPort;

    public AgentMemoryProfileService(IAgentMemoryProfileDao profileDao, LongTermMemoryPort longTermMemoryPort) {
        this.profileDao = profileDao;
        this.longTermMemoryPort = longTermMemoryPort;
    }

    public AgentMemoryProfile latest(String agentId) {
        if (blank(agentId)) return null;
        return profileDao.queryLatest(agentId.trim());
    }

    @Transactional
    public void updateFromResolvedCase(AiCase item, String reason) {
        if (item == null || blank(item.getAgentId()) || blank(item.getCaseId())) return;

        AgentMemoryProfile previous = profileDao.queryLatest(item.getAgentId());
        String caseId = item.getCaseId().trim();
        if (previous != null && containsCase(previous.getProfileJson(), caseId)) return;
        int version = previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1;
        JSONObject profile = previous == null ? emptyProfile() : parseProfile(previous.getProfileJson());
        addContribution(profile, "failure_patterns", caseId, item.getSummary(), item, reason);
        addContribution(profile, "resolution_patterns", caseId, item.getResolution(), item, reason);
        if (blank(item.getResolution())) {
            addContribution(profile, "business_rules", caseId, item.getExtractionReason(), item, reason);
        }

        String sourceCaseIds = mergeCaseIds(previous == null ? "" : previous.getSourceCaseIds(), caseId);
        LocalDateTime now = LocalDateTime.now();
        profileDao.insert(AgentMemoryProfile.builder()
                .agentId(item.getAgentId())
                .version(version)
                .profileJson(profile.toJSONString())
                .sourceCaseIds(sourceCaseIds)
                .createdAt(now)
                .updatedAt(now)
                .build());

        longTermMemoryPort.store(new LongTermMemoryPort.MemoryFact(
                item.getAgentId(), item.getAgentId(), "AGENT_PROFILE",
                renderProfile(profile, item.getAgentId(), version), "",
                "agent-profile:" + item.getAgentId() + ":v" + version,
                caseId, version));
    }

    private JSONObject emptyProfile() {
        JSONObject profile = new JSONObject();
        for (String section : SECTIONS) profile.put(section, new JSONArray());
        return profile;
    }

    private JSONObject parseProfile(String raw) {
        if (blank(raw)) return emptyProfile();
        try {
            JSONObject profile = JSON.parseObject(raw);
            JSONObject normalized = emptyProfile();
            for (String section : SECTIONS) {
                JSONArray values = profile == null ? null : profile.getJSONArray(section);
                normalized.put(section, values == null ? new JSONArray() : values);
            }
            return normalized;
        } catch (RuntimeException ignored) {
            return emptyProfile();
        }
    }

    private void addContribution(JSONObject profile, String section, String caseId, String text,
                                 AiCase item, String reason) {
        if (blank(text)) return;
        JSONArray values = profile.getJSONArray(section);
        JSONArray kept = new JSONArray();
        for (Object value : values) {
            if (!(value instanceof JSONObject object) || !caseId.equals(object.getString("caseId"))) {
                kept.add(value);
            }
        }
        JSONObject contribution = new JSONObject();
        contribution.put("caseId", caseId);
        contribution.put("text", compact(text));
        contribution.put("severity", blank(item.getSeverity()));
        contribution.put("status", "RESOLVED");
        contribution.put("reason", compact(reason));
        kept.add(contribution);
        profile.put(section, kept);
    }

    private String renderProfile(JSONObject profile, String agentId, int version) {
        StringBuilder output = new StringBuilder("Agent long-term profile\n");
        output.append("Agent: ").append(agentId).append("\nVersion: ").append(version).append('\n');
        for (String section : SECTIONS) {
            JSONArray values = profile.getJSONArray(section);
            if (values == null || values.isEmpty()) continue;
            output.append(section).append(":\n");
            for (Object value : values) {
                if (value instanceof JSONObject object) {
                    output.append("- ").append(object.getString("text"))
                            .append(" [case=").append(object.getString("caseId")).append("]\n");
                }
            }
        }
        return output.toString();
    }

    private String mergeCaseIds(String previous, String caseId) {
        Set<String> ids = new LinkedHashSet<>();
        if (!blank(previous)) for (String value : previous.split(",")) if (!value.isBlank()) ids.add(value.trim());
        ids.add(caseId);
        return String.join(",", ids);
    }

    private boolean containsCase(String profileJson, String caseId) {
        JSONObject profile = parseProfile(profileJson);
        for (String section : SECTIONS) {
            JSONArray values = profile.getJSONArray(section);
            if (values == null) continue;
            for (Object value : values) {
                if (value instanceof JSONObject object && caseId.equals(object.getString("caseId"))) return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
