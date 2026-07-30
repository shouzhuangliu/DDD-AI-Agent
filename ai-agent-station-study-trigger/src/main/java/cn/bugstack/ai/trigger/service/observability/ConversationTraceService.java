package cn.bugstack.ai.trigger.service.observability;

import cn.bugstack.ai.infrastructure.dao.IAgentExecutionDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemoryToolResult;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTask;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    @Resource
    private IAgentExecutionDao agentExecutionDao;

    @Resource
    private IMemoryToolResultDao memoryToolResultDao;

    @Resource
    private ISubagentTaskDao subagentTaskDao;

    public ConversationTrace trace(String agentId, String sessionId) {
        String safeAgentId = safe(agentId);
        String safeSessionId = safe(sessionId);
        List<TimelineEvent> timeline = new ArrayList<>();

        List<ChatMessage> messages = chatMessageDao.queryBySessionId(safeSessionId).stream()
                .filter(message -> safeAgentId.equals(message.getAgentId()))
                .toList();
        messages.forEach(message -> timeline.add(messageEvent(message)));

        llmLogDao.queryBySessionId(safeSessionId, 100).stream()
                .filter(log -> safeAgentId.equals(log.getAgentId()))
                .forEach(log -> timeline.add(llmEvent(log)));

        AgentExecution execution = agentExecutionDao.queryLatestBySession(safeAgentId, safeSessionId);
        if (execution != null && safeAgentId.equals(execution.getAgentId())) {
            timeline.add(routeEvent(execution));
            timeline.addAll(todoEvents(execution));
            if (notBlank(execution.getExecutionId())) {
                subagentTaskDao.queryByExecutionId(execution.getExecutionId(), 50).stream()
                        .filter(task -> safeAgentId.equals(task.getAgentId()))
                        .forEach(task -> timeline.add(subagentEvent(task)));
            }
        }

        memoryToolResultDao.queryBySession(safeSessionId, 50).stream()
                .filter(result -> safeAgentId.equals(result.getAgentId()))
                .forEach(result -> timeline.add(toolResultEvent(result)));

        feedbackDao.queryBySession(safeAgentId, safeSessionId, 50)
                .forEach(feedback -> timeline.add(feedbackEvent(feedback)));

        caseDao.queryBySession(safeAgentId, safeSessionId, 50)
                .forEach(item -> timeline.add(caseEvent(item)));

        timeline.sort(Comparator
                .comparing(TimelineEvent::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEvent::orderIndex));

        long toolCalls = timeline.stream()
                .filter(event -> "TOOL_CALL".equals(event.type()) || "TOOL_RESULT".equals(event.type()))
                .count();
        long llmCalls = timeline.stream().filter(event -> "LLM_CALL".equals(event.type())).count();
        long feedbackCount = timeline.stream().filter(event -> "FEEDBACK".equals(event.type())).count();
        long caseCount = timeline.stream().filter(event -> "CASE".equals(event.type())).count();
        boolean hasFailure = timeline.stream().anyMatch(event ->
                "FAILED".equals(event.status()) || "ERROR".equals(event.status()) || notBlank(event.errorMessage()));

        TraceSummary summary = new TraceSummary(
                messages.size(),
                (int) llmCalls,
                (int) toolCalls,
                (int) feedbackCount,
                (int) caseCount,
                hasFailure
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
                valueOr(message.getToolName(), ""),
                "TOOL_CALL".equals(type) ? inferToolSource(message.getToolName()) : "",
                valueOr(message.getToolArguments(), ""),
                preview(valueOr(message.getContent(), message.getToolCallsJson())),
                "",
                metadata("turn", valueOr(message.getTurn(), 0), "step", valueOr(message.getStep(), 0)),
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
                metadata(
                        "modelName", valueOr(log.getModelName(), ""),
                        "mode", valueOr(log.getMode(), ""),
                        "durationMs", valueOr(log.getDurationMs(), 0),
                        "totalTokens", valueOr(log.getTotalTokens(), 0)
                ),
                orderOf("LLM_CALL")
        );
    }

    private TimelineEvent routeEvent(AgentExecution execution) {
        return new TimelineEvent(
                "ROUTE",
                "路由决策：" + valueOr(execution.getRouteType(), "chat"),
                firstTime(execution.getStartedAt(), execution.getUpdatedAt(), execution.getCompletedAt()),
                normalizeStatus(execution.getStatus(), "RUNNING"),
                null,
                null,
                "",
                "",
                "routeType=" + valueOr(execution.getRouteType(), "-") + ", modelId=" + valueOr(execution.getModelId(), "-"),
                preview(valueOr(execution.getLastAssistantContent(), execution.getStateJson())),
                valueOr(execution.getErrorMessage(), ""),
                metadata(
                        "executionId", valueOr(execution.getExecutionId(), ""),
                        "routeType", valueOr(execution.getRouteType(), ""),
                        "modelId", valueOr(execution.getModelId(), ""),
                        "currentCycle", valueOr(execution.getCurrentCycle(), 0),
                        "currentStep", valueOr(execution.getCurrentStep(), 0),
                        "maxCycles", valueOr(execution.getMaxCycles(), 0),
                        "maxSteps", valueOr(execution.getMaxSteps(), 0)
                ),
                orderOf("ROUTE")
        );
    }

    private List<TimelineEvent> todoEvents(AgentExecution execution) {
        JSONArray todos = parseTodoArray(execution.getStateJson());
        List<TimelineEvent> events = new ArrayList<>();
        for (int i = 0; i < todos.size(); i++) {
            Object item = todos.get(i);
            if (!(item instanceof JSONObject todo)) continue;
            String content = valueOr(todo.getString("content"), valueOr(todo.getString("title"), "未命名 Todo"));
            events.add(new TimelineEvent(
                    "TODO",
                    "Todo：" + content,
                    firstTime(execution.getUpdatedAt(), execution.getStartedAt(), execution.getCompletedAt()),
                    normalizeStatus(todo.getString("status"), "PENDING"),
                    null,
                    null,
                    "",
                    "",
                    "",
                    preview(valueOr(todo.getString("detail"), content)),
                    "",
                    metadata(
                            "todoId", valueOr(todo.getString("todoId"), valueOr(todo.getString("id"), "")),
                            "content", content,
                            "executionId", valueOr(execution.getExecutionId(), "")
                    ),
                    orderOf("TODO")
            ));
        }
        return events;
    }

    private TimelineEvent toolResultEvent(MemoryToolResult result) {
        String error = valueOr(result.getErrorSummary(), "");
        return new TimelineEvent(
                "TOOL_RESULT",
                "工具结果：" + valueOr(result.getToolName(), "未知工具"),
                result.getCreatedAt(),
                notBlank(error) ? "FAILED" : "SUCCESS",
                result.getMessageId(),
                null,
                valueOr(result.getToolName(), ""),
                inferToolSource(result.getToolName()),
                valueOr(result.getKeyParametersJson(), ""),
                preview(result.getConclusion()),
                error,
                metadata("source", "memory_tool_result"),
                orderOf("TOOL_RESULT")
        );
    }

    private TimelineEvent subagentEvent(SubagentTask task) {
        return new TimelineEvent(
                "SUBAGENT",
                "子 Agent：" + valueOr(task.getDescription(), task.getTaskId()),
                firstTime(task.getStartedAt(), task.getUpdatedAt(), task.getCompletedAt()),
                normalizeStatus(task.getStatus(), "UNKNOWN"),
                null,
                null,
                "",
                "SUBAGENT",
                valueOr(task.getDescription(), ""),
                preview(task.getResult()),
                valueOr(task.getErrorMessage(), ""),
                metadata(
                        "taskId", valueOr(task.getTaskId(), ""),
                        "executionId", valueOr(task.getExecutionId(), ""),
                        "cancelRequested", valueOr(task.getCancelRequested(), false)
                ),
                orderOf("SUBAGENT")
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
                metadata("feedbackId", valueOr(feedback.getId(), 0L), "sourceType", valueOr(feedback.getSourceType(), "")),
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
                metadata("caseId", valueOr(item.getCaseId(), "")),
                orderOf("CASE")
        );
    }

    private JSONArray parseTodoArray(String stateJson) {
        if (!notBlank(stateJson)) return new JSONArray();
        try {
            JSONObject root = JSON.parseObject(stateJson);
            JSONArray todos = root == null ? null : root.getJSONArray("todos");
            return todos == null ? new JSONArray() : todos;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private String inferToolSource(String toolName) {
        String name = safe(toolName).toLowerCase();
        if (name.contains("mcp")) return "MCP";
        if (name.contains("subagent")) return "SUBAGENT";
        if (name.contains("skill") || name.contains("read")) return "SKILL";
        return name.isBlank() ? "" : "BUILTIN";
    }

    private int orderOf(String type) {
        return switch (type) {
            case "ROUTE" -> 5;
            case "USER_MESSAGE" -> 10;
            case "LLM_CALL" -> 20;
            case "TODO" -> 25;
            case "TOOL_CALL", "TOOL_RESULT" -> 30;
            case "SUBAGENT" -> 35;
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

    private static LocalDateTime firstTime(LocalDateTime... values) {
        for (LocalDateTime value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String normalizeStatus(String status, String fallback) {
        String text = safe(status);
        return text.isBlank() ? fallback : text.toUpperCase();
    }

    private static Map<String, Object> metadata(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return map;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isBlank();
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
