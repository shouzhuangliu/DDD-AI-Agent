package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AgentMemoryProfileService {

    private final IAgentMemoryProfileDao profileDao;
    private final IAgentMemoryCardDao cardDao;

    public AgentMemoryProfileService(IAgentMemoryProfileDao profileDao, IAgentMemoryCardDao cardDao) {
        this.profileDao = profileDao;
        this.cardDao = cardDao;
    }

    public AgentMemoryProfile latest(String agentId) {
        return blank(agentId) ? null : profileDao.queryLatest(agentId.trim());
    }

    @Transactional
    public AgentMemoryProfile compileLatest(String agentId) {
        if (blank(agentId)) throw new IllegalArgumentException("agentId 不能为空");
        List<AgentMemoryCard> cards = cardDao.queryPublishedByAgent(agentId);
        AgentMemoryProfile previous = profileDao.queryLatest(agentId);
        int version = previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1;
        JSONObject profile = new JSONObject();
        for (String section : List.of("capabilities", "failure_patterns", "business_rules", "resolution_patterns")) {
            profile.put(section, new JSONArray());
        }
        LinkedHashSet<String> caseIds = new LinkedHashSet<>();
        for (AgentMemoryCard card : cards == null ? List.<AgentMemoryCard>of() : cards) {
            JSONObject entry = new JSONObject();
            entry.put("memoryId", card.getMemoryId()); entry.put("version", card.getVersion());
            entry.put("caseId", safe(card.getSourceCaseId())); entry.put("title", safe(card.getTitle()));
            entry.put("summary", safe(card.getDescription())); entry.put("content", parseContent(card.getContentJson()));
            section(profile, card.getMemoryType()).add(entry);
            if (!blank(card.getSourceCaseId())) caseIds.add(card.getSourceCaseId());
        }
        LocalDateTime now = LocalDateTime.now();
        AgentMemoryProfile result = AgentMemoryProfile.builder().agentId(agentId).version(version)
                .profileJson(profile.toJSONString()).sourceCaseIds(String.join(",", caseIds))
                .createdAt(now).updatedAt(now).build();
        profileDao.insert(result);
        return result;
    }

    private JSONArray section(JSONObject profile, String memoryType) {
        return switch (safe(memoryType).toUpperCase()) {
            case "BUSINESS_RULE" -> profile.getJSONArray("business_rules");
            case "OPERATING_PLAYBOOK" -> profile.getJSONArray("resolution_patterns");
            case "CAPABILITY_BOUNDARY" -> profile.getJSONArray("capabilities");
            default -> profile.getJSONArray("failure_patterns");
        };
    }
    private Object parseContent(String raw) {
        try { return JSON.parse(raw); } catch (RuntimeException ignored) { return safe(raw); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }
}
