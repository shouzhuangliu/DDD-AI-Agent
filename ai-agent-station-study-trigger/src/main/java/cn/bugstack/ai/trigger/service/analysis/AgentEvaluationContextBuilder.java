package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import jakarta.annotation.Resource;
import cn.bugstack.ai.trigger.service.memory.MemoryQueryAdmissionPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentEvaluationContextBuilder {

    private static final Pattern RULE_HEADING = Pattern.compile("(?m)^#{1,4}\\s*(?:规则|Rule)\\s+([A-Za-z0-9_.:-]+)");

    private static final int RECENT_MESSAGE_LIMIT = 30;
    private static final int LONG_TERM_MEMORY_LIMIT = 5;

    private final AgentMemoryCatalogPort memoryCatalog;
    private final MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;

    @Resource
    private AgentRuntimeBindingService agentRuntimeBindingService;

    @Resource
    private SkillScannerService skillScannerService;

    public AgentEvaluationContextBuilder(AgentMemoryCatalogPort memoryCatalog,
                                         MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy) {
        this.memoryCatalog = memoryCatalog;
        this.memoryQueryAdmissionPolicy = memoryQueryAdmissionPolicy;
    }

    public String build(String agentId, List<ChatMessage> messages) {
        String safeAgentId = agentId == null ? "" : agentId;
        List<ChatMessage> safeMessages = messages == null ? List.of() : messages;
        String latestUserInput = safeMessages.stream()
                .filter(message -> "user".equalsIgnoreCase(message.getRole()))
                .reduce((ignored, latest) -> latest)
                .map(ChatMessage::getContent)
                .orElse("");

        StringBuilder evidence = new StringBuilder("agentId=").append(safeAgentId).append('\n');
        appendBoundBusinessSkills(evidence, safeAgentId);
        List<AgentMemoryCatalogPort.MemoryIndexItem> memoryIndexes = memoryQueryAdmissionPolicy.shouldRecall(latestUserInput)
                ? memoryCatalog.search(safeAgentId, latestUserInput, LONG_TERM_MEMORY_LIMIT)
                : List.of();
        List<AgentMemoryCatalogPort.MemoryContent> memories = memoryIndexes.isEmpty() ? List.of()
                : memoryCatalog.getPublished(safeAgentId, memoryIndexes.stream()
                .map(AgentMemoryCatalogPort.MemoryIndexItem::memoryId).toList());
        if (!memories.isEmpty()) {
            evidence.append("\n[长期记忆召回]\n");
            memories.forEach(memory -> evidence.append("- kind=").append(blank(memory.memoryType()))
                    .append(", memoryId=").append(blank(memory.memoryId()))
                    .append(", sourceCase=").append(blank(memory.sourceCaseId()))
                    .append(", content=").append(blank(memory.contentJson())).append('\n'));
        }

        evidence.append("\n[当前会话证据]\n");
        int start = Math.max(0, safeMessages.size() - RECENT_MESSAGE_LIMIT);
        for (int i = start; i < safeMessages.size(); i++) {
            ChatMessage message = safeMessages.get(i);
            evidence.append('[').append(message.getId()).append(' ').append(blank(message.getRole())).append("] ")
                    .append(blank(message.getContent())).append('\n');
        }
        return evidence.toString();
    }

    /** Exposes only the current Agent's bound Skill documents to the evaluator. */
    private void appendBoundBusinessSkills(StringBuilder evidence, String agentId) {
        evidence.append("\n[BOUND BUSINESS SKILLS]\n");
        if (agentRuntimeBindingService == null || skillScannerService == null) {
            evidence.append("- unavailable\n");
            return;
        }
        try {
            AgentRuntimeBindingService.AgentRuntimeBindings bindings =
                    agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), true);
            List<String> skillIds = bindings.getSkillIds() == null ? List.of() : bindings.getSkillIds();
            List<String> mcpIds = bindings.getMcpIds() == null ? List.of() : bindings.getMcpIds();
            evidence.append("[BOUND BUSINESS MCP]\n");
            if (mcpIds.isEmpty()) {
                evidence.append("- none; Case 只能停留在 Feedback，不能升级\n");
            } else {
                mcpIds.forEach(mcpId -> evidence.append("- mcpId=").append(mcpId)
                        .append(", result must come from a recorded tool message\n"));
            }
            if (skillIds.isEmpty()) {
                evidence.append("- none; cases are not eligible without a bound business Skill\n");
                return;
            }
            int remaining = 24000;
            for (String skillId : skillIds) {
                if (remaining <= 0) break;
                SkillScannerService.SkillInfo skill = skillScannerService.readSkillFromWorkDir(
                        bindings.getWorkspace().toString(), skillId);
                if (skill == null) {
                    evidence.append("- ").append(skillId).append(": document unavailable\n");
                    continue;
                }
                String content = skill.getContent() == null ? "" : skill.getContent();
                int allowed = Math.min(content.length(), remaining);
                Set<String> ruleIds = extractRuleIds(content);
                evidence.append("- skillId=").append(skill.getSkillId())
                        .append(", name=").append(skill.getSkillName())
                        .append(", description=").append(skill.getDescription())
                        .append(", version=workspace-bound")
                        .append(", ruleIds=").append(ruleIds).append('\n')
                        .append(content, 0, allowed).append('\n');
                evidence.append("[BOUND BUSINESS SKILL RULES]\n");
                for (String ruleId : ruleIds) {
                    evidence.append("- skillId=").append(skill.getSkillId())
                            .append(", version=workspace-bound, ruleId=").append(ruleId)
                            .append(", allowedEvidence=user|operator|tool, body=see bound SKILL.md\n");
                }
                remaining -= allowed;
            }
        } catch (Exception exception) {
            evidence.append("- unavailable; cases are not eligible without verified Skill context\n");
        }
    }

    public boolean hasBoundBusinessSkills(String agentId) {
        if (agentRuntimeBindingService == null || skillScannerService == null) return false;
        try {
            var bindings = agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), true);
            if (bindings.getSkillIds() == null || bindings.getSkillIds().isEmpty()
                    || bindings.getWorkspace() == null) return false;
            return bindings.getSkillIds().stream()
                    .map(boundSkillId -> skillScannerService.readSkillFromWorkDir(
                            bindings.getWorkspace().toString(), boundSkillId))
                    .anyMatch(java.util.Objects::nonNull);
        } catch (Exception exception) {
            return false;
        }
    }

    public boolean hasBoundBusinessSkill(String agentId, String skillId) {
        if (skillId == null || skillId.isBlank()
                || agentRuntimeBindingService == null || skillScannerService == null) return false;
        try {
            var bindings = agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), true);
            if (bindings.getSkillIds() == null || bindings.getSkillIds().isEmpty()
                    || bindings.getWorkspace() == null) return false;
            return bindings.getSkillIds().stream()
                    .filter(boundSkillId -> boundSkillId.equals(skillId.trim()))
                    .map(matchedSkillId -> skillScannerService.readSkillFromWorkDir(
                            bindings.getWorkspace().toString(), matchedSkillId))
                    .anyMatch(java.util.Objects::nonNull);
        } catch (Exception exception) {
            return false;
        }
    }

    /** Returns the exact Skill and rule IDs available to the current Agent. */
    public CaseEvidenceGate.BoundSkillContext boundSkillContext(String agentId) {
        if (agentRuntimeBindingService == null || skillScannerService == null) {
            return CaseEvidenceGate.BoundSkillContext.empty();
        }
        try {
            var bindings = agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), true);
            List<String> skillIds = bindings.getSkillIds() == null ? List.of() : bindings.getSkillIds();
            if (skillIds.size() != 1 || bindings.getWorkspace() == null) {
                return new CaseEvidenceGate.BoundSkillContext(agentId, "", Set.of());
            }
            String skillId = skillIds.get(0);
            SkillScannerService.SkillInfo skill = skillScannerService.readSkillFromWorkDir(
                    bindings.getWorkspace().toString(), skillId);
            if (skill == null) return new CaseEvidenceGate.BoundSkillContext(agentId, skillId, Set.of());
            return new CaseEvidenceGate.BoundSkillContext(agentId, skillId, extractRuleIds(skill.getContent()));
        } catch (Exception exception) {
            return CaseEvidenceGate.BoundSkillContext.empty();
        }
    }

    private Set<String> extractRuleIds(String content) {
        Set<String> ruleIds = new LinkedHashSet<>();
        if (content == null) return ruleIds;
        Matcher matcher = RULE_HEADING.matcher(content);
        while (matcher.find()) ruleIds.add(matcher.group(1));
        return Set.copyOf(ruleIds);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
