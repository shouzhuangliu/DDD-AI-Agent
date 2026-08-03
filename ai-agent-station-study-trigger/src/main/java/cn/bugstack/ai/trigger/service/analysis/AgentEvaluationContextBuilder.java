package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import jakarta.annotation.Resource;
import cn.bugstack.ai.trigger.service.memory.MemoryQueryAdmissionPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentEvaluationContextBuilder {

    private static final int RECENT_MESSAGE_LIMIT = 30;
    private static final int LONG_TERM_MEMORY_LIMIT = 5;

    private final LongTermMemoryPort longTermMemoryPort;
    private final MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;

    @Resource
    private AgentRuntimeBindingService agentRuntimeBindingService;

    @Resource
    private SkillScannerService skillScannerService;

    public AgentEvaluationContextBuilder(LongTermMemoryPort longTermMemoryPort,
                                         MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy) {
        this.longTermMemoryPort = longTermMemoryPort;
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
        List<LongTermMemoryPort.MemoryFact> memories = memoryQueryAdmissionPolicy.shouldRecall(latestUserInput)
                ? longTermMemoryPort.retrieve(safeAgentId, safeAgentId, latestUserInput, LONG_TERM_MEMORY_LIMIT)
                : List.of();
        if (!memories.isEmpty()) {
            evidence.append("\n[长期记忆召回]\n");
            memories.forEach(memory -> evidence.append("- kind=").append(blank(memory.kind()))
                    .append(", sourceSession=").append(blank(memory.sourceSessionId()))
                    .append(", content=").append(blank(memory.content())).append('\n'));
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
                    agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), false);
            List<String> skillIds = bindings.getSkillIds() == null ? List.of() : bindings.getSkillIds();
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
                evidence.append("- skillId=").append(skill.getSkillId())
                        .append(", name=").append(skill.getSkillName())
                        .append(", description=").append(skill.getDescription()).append('\n')
                        .append(content, 0, allowed).append('\n');
                remaining -= allowed;
            }
        } catch (Exception exception) {
            evidence.append("- unavailable; cases are not eligible without verified Skill context\n");
        }
    }

    public boolean hasBoundBusinessSkills(String agentId) {
        if (agentRuntimeBindingService == null || skillScannerService == null) return false;
        try {
            var bindings = agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), false);
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
            var bindings = agentRuntimeBindingService.assemble(agentId, System.getProperty("user.dir"), false);
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

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
