package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentEvaluationContextBuilder {

    private static final int RECENT_MESSAGE_LIMIT = 30;
    private static final int LONG_TERM_MEMORY_LIMIT = 5;

    private final LongTermMemoryPort longTermMemoryPort;

    public AgentEvaluationContextBuilder(LongTermMemoryPort longTermMemoryPort) {
        this.longTermMemoryPort = longTermMemoryPort;
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
        List<LongTermMemoryPort.MemoryFact> memories = longTermMemoryPort.retrieve(
                safeAgentId, safeAgentId, latestUserInput, LONG_TERM_MEMORY_LIMIT);
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

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
