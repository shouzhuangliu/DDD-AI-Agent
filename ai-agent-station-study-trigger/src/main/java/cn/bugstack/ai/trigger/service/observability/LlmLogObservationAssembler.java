package cn.bugstack.ai.trigger.service.observability;

import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class LlmLogObservationAssembler {

    public List<AgentGroup> group(List<AiLlmLog> logs, Function<String, List<ChatMessage>> messageLoader) {
        Map<String, Map<String, SessionGroupBuilder>> grouped = new LinkedHashMap<>();
        for (AiLlmLog log : logs == null ? List.<AiLlmLog>of() : logs) {
            String agentId = safe(log.getAgentId(), "UNKNOWN_AGENT");
            String sessionId = safe(log.getSessionId(), "UNKNOWN_SESSION");
            grouped.computeIfAbsent(agentId, key -> new LinkedHashMap<>())
                    .computeIfAbsent(sessionId, key -> new SessionGroupBuilder(agentId, sessionId))
                    .logs.add(log);
        }
        List<AgentGroup> result = new ArrayList<>();
        grouped.forEach((agentId, sessions) -> {
            List<SessionGroup> sessionGroups = sessions.values().stream()
                    .map(builder -> builder.build(messageLoader.apply(builder.sessionId)))
                    .sorted(Comparator.comparing(SessionGroup::lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            int totalCalls = sessionGroups.stream().mapToInt(session -> session.logs().size()).sum();
            int totalTokens = sessionGroups.stream().flatMap(session -> session.logs().stream())
                    .map(AiLlmLog::getTotalTokens).filter(value -> value != null).mapToInt(Integer::intValue).sum();
            result.add(new AgentGroup(agentId, totalCalls, sessionGroups.size(), totalTokens, sessionGroups));
        });
        result.sort(Comparator.comparing(AgentGroup::totalCalls).reversed());
        return result;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static class SessionGroupBuilder {
        private final String agentId;
        private final String sessionId;
        private final List<AiLlmLog> logs = new ArrayList<>();

        private SessionGroupBuilder(String agentId, String sessionId) {
            this.agentId = agentId;
            this.sessionId = sessionId;
        }

        private SessionGroup build(List<ChatMessage> messages) {
            logs.sort(Comparator.comparing(AiLlmLog::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            List<TraceMessage> trace = (messages == null ? List.<ChatMessage>of() : messages).stream()
                    .filter(message -> agentId.equals(message.getAgentId()))
                    .map(message -> new TraceMessage(message.getId(), message.getTurn(), message.getStep(), message.getRole(),
                            message.getContent(), message.getToolCallId(), message.getToolName(), message.getToolArguments(),
                            message.getToolCallsJson(), message.getCreatedAt()))
                    .toList();
            LocalDateTime lastSeenAt = logs.stream().map(AiLlmLog::getCreatedAt).filter(value -> value != null)
                    .max(LocalDateTime::compareTo).orElse(null);
            int totalTokens = logs.stream().map(AiLlmLog::getTotalTokens).filter(value -> value != null).mapToInt(Integer::intValue).sum();
            int toolCalls = (int) trace.stream().filter(message -> "tool".equalsIgnoreCase(message.role())
                    || (message.toolCallsJson() != null && !message.toolCallsJson().isBlank())).count();
            return new SessionGroup(sessionId, lastSeenAt, logs.size(), totalTokens, toolCalls, logs, trace);
        }
    }

    public record AgentGroup(String agentId, int totalCalls, int sessionCount, int totalTokens, List<SessionGroup> sessions) {}
    public record SessionGroup(String sessionId, LocalDateTime lastSeenAt, int callCount, int totalTokens, int toolCalls,
                               List<AiLlmLog> logs, List<TraceMessage> messages) {}
    public record TraceMessage(Long id, Integer turn, Integer step, String role, String content, String toolCallId,
                               String toolName, String toolArguments, String toolCallsJson, LocalDateTime createdAt) {}
}
