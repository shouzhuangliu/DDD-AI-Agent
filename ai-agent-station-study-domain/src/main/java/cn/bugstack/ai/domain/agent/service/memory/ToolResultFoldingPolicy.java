package cn.bugstack.ai.domain.agent.service.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用结果的独立折叠策略。
 *
 * <p>普通消息折叠只负责缩短上下文；工具折叠还必须保留调用与结果的配对，
 * 并把结果标记为历史快照。这样模型需要最新库存、订单或 Feedback 时，
 * 不会把折叠后的历史结果误当成实时事实。</p>
 */
public final class ToolResultFoldingPolicy {

    private ToolResultFoldingPolicy() {
    }

    /**
     * 折叠一个已经确认属于历史范围的 assistant.tool_calls + tool 结果交换。
     */
    public static boolean foldExchange(List<Map<String, Object>> messages,
                                       int assistantIndex,
                                       ToolFoldConfig config) {
        if (messages == null || assistantIndex < 0 || assistantIndex >= messages.size()) return false;
        Map<String, Object> assistant = messages.get(assistantIndex);
        Object rawCalls = assistant.get("tool_calls");
        if (!(rawCalls instanceof List<?> calls)) return false;

        Map<String, String> namesById = new LinkedHashMap<>();
        for (Object call : calls) {
            if (!(call instanceof Map<?, ?> callMap)) continue;
            String id = value(callMap, "id");
            Object functionObject = callMap.get("function");
            String name = "unknown";
            if (functionObject instanceof Map<?, ?> function) {
                name = value(function, "name");
                if (function instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked") Map<String, Object> mutable = (Map<String, Object>) function;
                    mutable.put("arguments", "{}");
                }
            }
            if (!id.isBlank()) namesById.put(id, name.isBlank() ? "unknown" : name);
        }

        boolean folded = false;
        for (int index = assistantIndex + 1; index < messages.size(); index++) {
            Map<String, Object> message = messages.get(index);
            if (!"tool".equals(message.get("role"))) break;
            String id = String.valueOf(message.getOrDefault("tool_call_id", ""));
            String name = namesById.get(id);
            if (name != null) folded |= foldToolMessage(message, name, id, config, true);
        }
        return folded;
    }

    /**
     * 对当前上下文中的单个工具结果执行大小限制。短结果保持原文，避免无意义地增加引用提示。
     */
    public static boolean foldSingleToolResult(Map<String, Object> message, ToolFoldConfig config) {
        if (message == null || !"tool".equals(message.get("role"))) return false;
        String content = String.valueOf(message.getOrDefault("content", ""));
        if (content.length() <= config.maxResultChars()) return false;
        return foldToolMessage(message,
                String.valueOf(message.getOrDefault("name", "unknown")),
                String.valueOf(message.getOrDefault("tool_call_id", "unknown")),
                config, false);
    }

    private static boolean foldToolMessage(Map<String, Object> message,
                                           String toolName,
                                           String toolCallId,
                                           ToolFoldConfig config,
                                           boolean force) {
        if (Boolean.TRUE.equals(message.get("tool_result_folded"))) return false;
        String content = String.valueOf(message.getOrDefault("content", ""));
        if (!force && content.length() <= config.maxResultChars()) return false;
        message.put("content", FoldedToolReference.render(toolName, toolCallId, content,
                config.previewHeadChars(), config.previewTailChars()));
        message.put("tool_result_folded", true);
        message.put("tool_result_freshness", "HISTORICAL");
        message.put("tool_result_retrieve_id", toolCallId);
        return true;
    }

    private static String value(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
