package cn.bugstack.ai.domain.agent.service.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 保证发送给模型的 assistant.tool_calls 与 tool 回执始终成对。
 */
public final class HistoryMessageSanitizer {

    private HistoryMessageSanitizer() {
    }

    public static List<Map<String, Object>> sanitize(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<Map<String, Object>> copied = new ArrayList<>();
        for (Map<String, Object> message : messages) copied.add(copyMap(message));

        List<Map<String, Object>> output = new ArrayList<>();
        Set<String> activeIds = new HashSet<>();
        Set<String> pendingIds = new HashSet<>();
        int assistantIndex = -1;

        for (Map<String, Object> message : copied) {
            String role = String.valueOf(message.getOrDefault("role", ""));
            if ("tool".equals(role)) {
                String toolCallId = String.valueOf(message.getOrDefault("tool_call_id", ""));
                if (!pendingIds.remove(toolCallId)) continue;
                output.add(message);
                continue;
            }

            if (!pendingIds.isEmpty()) {
                removeIncompleteExchange(output, assistantIndex, activeIds);
                pendingIds.clear();
                activeIds.clear();
                assistantIndex = -1;
            }

            if ("assistant".equals(role)) {
                Set<String> ids = extractToolCallIds(message.get("tool_calls"));
                if (!ids.isEmpty()) {
                    activeIds.addAll(ids);
                    pendingIds.addAll(ids);
                    assistantIndex = output.size();
                }
            }
            output.add(message);
        }

        if (!pendingIds.isEmpty()) removeIncompleteExchange(output, assistantIndex, activeIds);
        return output;
    }

    private static void removeIncompleteExchange(List<Map<String, Object>> output,
                                                 int assistantIndex,
                                                 Set<String> activeIds) {
        if (assistantIndex >= 0 && assistantIndex < output.size()) {
            output.get(assistantIndex).remove("tool_calls");
        }
        output.removeIf(message -> "tool".equals(message.get("role"))
                && activeIds.contains(String.valueOf(message.getOrDefault("tool_call_id", ""))));
    }

    private static Set<String> extractToolCallIds(Object rawToolCalls) {
        Set<String> ids = new HashSet<>();
        if (!(rawToolCalls instanceof List<?> calls)) return ids;
        for (Object call : calls) {
            if (call instanceof Map<?, ?> map && map.get("id") != null) {
                String id = String.valueOf(map.get("id"));
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (source == null) return target;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((key, item) -> nested.put(String.valueOf(key), copyValue(item)));
                target.put(entry.getKey(), nested);
            } else if (value instanceof List<?> list) {
                target.put(entry.getKey(), list.stream().map(HistoryMessageSanitizer::copyValue).toList());
            } else {
                target.put(entry.getKey(), value);
            }
        }
        return target;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), copyValue(item)));
            return nested;
        }
        if (value instanceof List<?> list) return list.stream().map(HistoryMessageSanitizer::copyValue).toList();
        return value;
    }
}
