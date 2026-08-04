package cn.bugstack.ai.trigger.service.agent;

import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AgentBusinessContextService {

    private static final Pattern DOMAIN_TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z]{3,}|\\d{2,}");

    @Resource
    private AgentRuntimeBindingService agentRuntimeBindingService;

    public Set<String> collectKeywords(String agentId) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (agentId == null || agentId.isBlank()) return keywords;
        try {
            AgentRuntimeBindingService.AgentRuntimeBindings bindings =
                    agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), false);
            var agent = bindings.getAgent();
            if (agent == null) return keywords;
            appendTokens(keywords, agent.getAgentName());
            appendTokens(keywords, agent.getDescription());
            for (String skillId : bindings.getSkillIds()) {
                SkillScannerService.SkillInfo metadata = bindings.getSkillMetadataById().get(skillId);
                if (metadata == null) continue;
                appendTokens(keywords, metadata.getSkillId());
                appendTokens(keywords, metadata.getSkillName());
                appendTokens(keywords, metadata.getDescription());
            }
        } catch (Exception exception) {
            log.debug("收集 Agent 业务上下文失败 agentId={}", agentId, exception);
        }
        return keywords;
    }

    /**
     * Business feedback is only meaningful when the Agent has at least one
     * enabled Skill whose metadata can be loaded into its workspace.
     */
    public boolean hasBoundBusinessSkill(String agentId) {
        return !boundBusinessSkillId(agentId).isBlank();
    }

    /**
     * Returns the first active Skill whose metadata can be loaded for this
     * Agent. Manual Feedback promotion uses this exact ID so the generated
     * Case remains visible to the Agent-scoped dashboard queries.
     */
    public String boundBusinessSkillId(String agentId) {
        if (agentId == null || agentId.isBlank()) return "";
        try {
            AgentRuntimeBindingService.AgentRuntimeBindings bindings =
                    agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), false);
            if (bindings.getSkillIds() == null || bindings.getSkillIds().isEmpty()
                    || bindings.getSkillMetadataById() == null) return "";
            return bindings.getSkillIds().stream()
                    .filter(skillId -> skillId != null && !skillId.isBlank())
                    .filter(skillId -> bindings.getSkillMetadataById().containsKey(skillId))
                    .findFirst().orElse("");
        } catch (Exception exception) {
            log.debug("Agent 业务 Skill 上下文不可用，无法生成绑定 Skill ID agentId={}", agentId, exception);
            return "";
        }
    }

    private void appendTokens(Set<String> keywords, String rawText) {
        if (rawText == null || rawText.isBlank()) return;
        Matcher matcher = DOMAIN_TOKEN.matcher(rawText.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 2) continue;
            if (containsAny(token, "skill", "agent", "demo", "issue", "report", "feedback")) continue;
            keywords.add(token);
        }
    }

    private boolean containsAny(String text, String... words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
