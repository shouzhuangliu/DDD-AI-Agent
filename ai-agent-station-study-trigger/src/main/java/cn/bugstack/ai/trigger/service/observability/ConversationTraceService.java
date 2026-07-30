package cn.bugstack.ai.trigger.service.observability;

import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ConversationTraceService {

    @Resource
    private IChatMessageDao chatMessageDao;

    @Resource
    private IAiLlmLogDao llmLogDao;

    @Resource
    private IAiFeedbackDao feedbackDao;

    @Resource
    private IAiCaseDao caseDao;

    public ConversationTrace trace(String agentId, String sessionId) {
        String safeAgentId = safe(agentId);
        String safeSessionId = safe(sessionId);
        List<TimelineEvent> timeline = new java.util.ArrayList<>();
        List<ChatMessage> messages = chatMessageDao.queryBySessionId(safeSessionId).stream()
                .filter(message -> safeAgentId.equals(message.getAgentId()))
                .toList();
        messages.forEach(message -> timeline.add(messageEvent(message)));
        llmLogDao.queryBySessionId(safeSessionId, 100).stream()
                .filter(log -> safeAgentId.equals(log.getAgentId()))
                .forEach(log -> timeline.add(llmEvent(log)));
        feedbackDao.queryBySession(safeAgentId, safeSessionId, 50)
                .forEach(feedback -> timeline.add(feedbackEvent(feedback)));
        caseDao.queryBySession(safeAgentId, safeSessionId, 50)
                .forEach(item -> timeline.add(caseEvent(item)));
        timeline.sort(Comparator
                .comparing(TimelineEvent::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEvent::orderIndex));
        long toolCalls = timeline.stream().filter(event -> "TOOL_CALL".equals(event.type())).count();
        long llmCalls = timeline.stream().filter(event -> "LLM_CALL".equals(event.type())).count();
        long feedbackCount = timeline.stream().filter(event -> "FEEDBACK".equals(event.type())).count();
        long caseCount = timeline.stream().filter(event -> "CASE".equals(event.type())).count();
        TraceSummary summary = new TraceSummary(
                messages.size(),
                (int) llmCalls,
                (int) toolCalls,
                (int) feedbackCount,
                (int) caseCount,
                timeline.stream().map(TimelineEvent::status).anyMatch(status -> "FAILED".equals(status) || "ERROR".equals(status))
        );
        return new ConversationTrace(safeAgentId, safeSessionId, summary, timeline);
    }

    private TimelineEvent messageEvent(ChatMessage message) {
        String role = safe(message.getRole()).toLowerCase();
        String type = "tool".equals(role) ? "TOOL_CALL" : ("assistant".equals(role) ? "ASSISTANT_MESSAGE" : "USER_MESSAGE");
        String title = switch (type) {
            case "TOOL_CALL" -> "工具调用：" + valueOr(message.getToolName(), "未知工具");
            case "ASSISTANT_MESSAGE" -> "助手回复";
            default -> "用户消息";
        };
        return new TimelineEvent(
                type,
                title,
                message.getCreatedAt(),
                "SUCCESS",
                message.getId(),
                null,
                message.getToolName(),
                "TOOL_CALL".equals(type) ? inferToolSource(message.getToolName()) : "",
                message.getToolArguments(),
                preview(valueOr(message.getContent(), message.getToolCallsJson())),
                "",
                Map.of("turn", valueOr(message.getTurn(), 0), "step", valueOr(message.getStep(), 0)),
                orderOf(type)
        );
    }

    private TimelineEvent llmEvent(AiLlmLog log) {
        String status = "success".equalsIgnoreCase(safe(log.getStatus())) ? "SUCCESS" : "FAILED";
        return new TimelineEvent(
                "LLM_CALL",
                "模型调用：" + valueOr(log.getModelName(), "未知模型"),
                log.getCreatedAt(),
                status,
                null,
                log.getId(),
                "",
                "",
                "mode=" + valueOr(log.getMode(), "-"),
                preview("tokens=" + valueOr(log.getTotalTokens(), 0) + ", durationMs=" + valueOr(log.getDurationMs(), 0)),
                valueOr(log.getErrorMessage(), ""),
                Map.of(
                        "modelName", valueOr(log.getModelName(), ""),
                        "mode", valueOr(log.getMode(), ""),
                        "durationMs", valueOr(log.getDurationMs(), 0),
                        "totalTokens", valueOr(log.getTotalTokens(), 0)
                ),
                orderOf("LLM_CALL")
        );
    }

    private TimelineEvent feedbackEvent(AiFeedback feedback) {
        return new TimelineEvent(
                "FEEDBACK",
                "反馈：" + valueOr(feedback.getFeedbackType(), "业务反馈"),
                feedback.getCreatedAt(),
                valueOr(feedback.getStatus(), "OPEN"),
                null,
                null,
                "",
                "",
                "",
                preview(feedback.getMessage()),
                "",
                Map.of("feedbackId", valueOr(feedback.getId(), 0L), "sourceType", valueOr(feedback.getSourceType(), "")),
                orderOf("FEEDBACK")
        );
    }

    private TimelineEvent caseEvent(AiCase item) {
        return new TimelineEvent(
                "CASE",
                "Case：" + valueOr(item.getTitle(), item.getCaseId()),
                item.getCreatedAt(),
                valueOr(item.getStatus(), "CANDIDATE"),
                null,
                null,
                "",
                "",
                "",
                preview(item.getSummary()),
                "",
                Map.of("caseId", valueOr(item.getCaseId(), "")),
                orderOf("CASE")
        );
    }

    private String inferToolSource(String toolName) {
        String name = safe(toolName).toLowerCase();
        if (name.contains("mcp")) return "MCP";
        if (name.contains("skill") || name.contains("read")) return "SKILL";
        return name.isBlank() ? "" : "BUILTIN";
    }

    private int orderOf(String type) {
        return switch (type) {
            case "USER_MESSAGE" -> 10;
            case "LLM_CALL" -> 20;
            case "TOOL_CALL" -> 30;
            case "ASSISTANT_MESSAGE" -> 40;
            case "FEEDBACK" -> 50;
            case "CASE" -> 60;
            default -> 99;
        };
    }

    private String preview(String value) {
        String text = safe(value).replaceAll("\\s+", " ").trim();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> T valueOr(T value, T fallback) {
        return Objects.requireNonNullElse(value, fallback);
    }

    public record ConversationTrace(String agentId, String sessionId, TraceSummary summary, List<TimelineEvent> timeline) {
    }

    public record TraceSummary(int messageCount, int llmCalls, int toolCalls, int feedbackCount, int caseCount, boolean hasFailure) {
    }

    public record TimelineEvent(String type, String title, LocalDateTime occurredAt, String status,
                                Long messageId, Long llmLogId, String toolName, String toolSource,
                                String input, String outputPreview, String errorMessage,
                                Map<String, Object> metadata, int orderIndex) {
    }
}
