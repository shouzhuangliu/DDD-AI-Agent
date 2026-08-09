package cn.bugstack.ai.domain.agent.service.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在持久化领域消息和 Spring AI 消息之间做结构化转换。
 */
public final class HistoryMessageMapper {

    private HistoryMessageMapper() {
    }

    public static List<Map<String, Object>> toMaps(List<HistoryMessage> history) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (history == null) return result;
        for (HistoryMessage message : history) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("role", message.getRole());
            mapped.put("content", message.getContent() == null ? "" : message.getContent());
            if (hasText(message.getToolCallId())) mapped.put("tool_call_id", message.getToolCallId());
            if (hasText(message.getToolName())) mapped.put("name", message.getToolName());
            if (hasText(message.getToolArguments())) mapped.put("tool_arguments", message.getToolArguments());
            if (hasText(message.getToolCallsJson())) {
                Object calls = parseToolCalls(message.getToolCallsJson());
                if (calls != null) mapped.put("tool_calls", calls);
            }
            result.add(mapped);
        }
        return result;
    }

    public static List<Message> toSpringMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> sanitized = HistoryMessageSanitizer.sanitize(messages);
        List<Message> result = new ArrayList<>();
        for (int index = 0; index < sanitized.size(); index++) {
            Map<String, Object> message = sanitized.get(index);
            String role = String.valueOf(message.getOrDefault("role", "user"));
            String content = String.valueOf(message.getOrDefault("content", ""));
            if ("user".equals(role)) {
                result.add(new UserMessage(content));
                continue;
            }
            if ("assistant".equals(role)) {
                result.add(toAssistantMessage(message));
                continue;
            }
            if ("tool".equals(role)) {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                int cursor = index;
                while (cursor < sanitized.size() && "tool".equals(sanitized.get(cursor).get("role"))) {
                    Map<String, Object> tool = sanitized.get(cursor++);
                    responses.add(new ToolResponseMessage.ToolResponse(
                            String.valueOf(tool.getOrDefault("tool_call_id", "")),
                            String.valueOf(tool.getOrDefault("name", "")),
                            String.valueOf(tool.getOrDefault("content", ""))));
                }
                result.add(new ToolResponseMessage(responses));
                index = cursor - 1;
            }
        }
        return result;
    }

    /** 将模型返回的 assistant 消息转为下一轮折叠使用的规范 Map。 */
    public static Map<String, Object> toMap(AssistantMessage message) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("role", "assistant");
        mapped.put("content", message == null || message.getText() == null ? "" : message.getText());
        if (message == null || message.getToolCalls() == null || message.getToolCalls().isEmpty()) return mapped;
        List<Map<String, Object>> calls = new ArrayList<>();
        for (AssistantMessage.ToolCall call : message.getToolCalls()) {
            calls.add(Map.of("id", call.id(), "type", call.type(),
                    "function", Map.of("name", call.name(), "arguments", call.arguments())));
        }
        mapped.put("tool_calls", calls);
        return mapped;
    }

    public static Map<String, Object> toolMap(String toolCallId, String name, String content) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("role", "tool");
        mapped.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        mapped.put("name", name == null ? "" : name);
        mapped.put("content", content == null ? "" : content);
        return mapped;
    }

    private static AssistantMessage toAssistantMessage(Map<String, Object> message) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        Object rawCalls = message.get("tool_calls");
        if (rawCalls instanceof String json) rawCalls = parseToolCalls(json);
        if (rawCalls instanceof List<?> list) {
            for (Object rawCall : list) {
                if (!(rawCall instanceof Map<?, ?> call)) continue;
                String id = String.valueOf(valueOrDefault(call, "id", ""));
                String type = String.valueOf(valueOrDefault(call, "type", "function"));
                Object rawFunction = call.get("function");
                String name = "";
                String arguments = "{}";
                if (rawFunction instanceof Map<?, ?> function) {
                    name = String.valueOf(valueOrDefault(function, "name", ""));
                    arguments = String.valueOf(valueOrDefault(function, "arguments", "{}"));
                }
                calls.add(new AssistantMessage.ToolCall(id, type, name, arguments));
            }
        }
        return calls.isEmpty() ? new AssistantMessage(String.valueOf(message.getOrDefault("content", "")))
                : new AssistantMessage(String.valueOf(message.getOrDefault("content", "")), Map.of(), calls);
    }

    private static Object parseToolCalls(String json) {
        try {
            JSONArray array = JSON.parseArray(json);
            return array == null ? null : array.stream().map(HistoryMessageMapper::toJavaValue).toList();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object toJavaValue(Object value) {
        if (value instanceof JSONObject object) {
            Map<String, Object> map = new LinkedHashMap<>();
            object.forEach((key, item) -> map.put(key, toJavaValue(item)));
            return map;
        }
        if (value instanceof JSONArray array) return array.stream().map(HistoryMessageMapper::toJavaValue).toList();
        return value;
    }

    private static Object valueOrDefault(Map<?, ?> map, Object key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
