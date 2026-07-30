package cn.bugstack.ai.trigger.service.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
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
    private IAgentRepository agentRepository;

    @Resource
    private SkillScannerService skillScannerService;

    @Resource
    private AgentWorkspaceService agentWorkspaceService;

    public Set<String> collectKeywords(String agentId) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (agentId == null || agentId.isBlank()) return keywords;
        try {
            AiAgentVO agent = agentRepository.queryAgentById(agentId);
            if (agent == null) return keywords;
            appendTokens(keywords, agent.getAgentName());
            appendTokens(keywords, agent.getDescription());
            String workspace = agentWorkspaceService
                    .resolveWorkDir(agentId, agent.getWorkDir(), System.getProperty("user.dir"))
                    .toString();
            for (String skillId : agentRepository.queryBoundSkillIds(agentId)) {
                SkillScannerService.SkillInfo metadata = skillScannerService.readSkillMetadataFromWorkDir(workspace, skillId);
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
