package cn.bugstack.ai.domain.agent.service.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推理前的确定性历史压缩管线。只修改发送给模型的内存副本，数据库原文由调用记录负责保存。
 */
@Slf4j
public final class MemoryFoldingPipeline {

    private static final int LEAF_MIN_CHARS = 300;
    private static final int KEEP_HEAD_CHARS = 200;
    private static final int KEEP_TAIL_CHARS = 100;

    private MemoryFoldingPipeline() {
    }

    public static List<Map<String, Object>> fold(List<Map<String, Object>> messages) {
        return fold(messages, FoldConfig.defaultProfile());
    }

    public static List<Map<String, Object>> fold(List<Map<String, Object>> messages, FoldConfig config) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> message : messages) copy.add(copyMap(message));

        List<Map<String, Object>> current = HistoryMessageSanitizer.sanitize(copy);
        current = foldCurrentRound(current, config);
        current = HistoryMessageSanitizer.sanitize(current);
        current = stripHistoricalRounds(current, config);
        current = HistoryMessageSanitizer.sanitize(current);
        current = capIndividualMessages(current, config.maxMessageChars());
        current = trimToFinalBudget(current, config.finalTriggerChars());
        return HistoryMessageSanitizer.sanitize(current);
    }

    private static List<Map<String, Object>> foldCurrentRound(List<Map<String, Object>> messages,
                                                                FoldConfig config) {
        int lastUser = lastIndexOfRole(messages, "user");
        if (lastUser < 0) return messages;
        List<Integer> steps = new ArrayList<>();
        for (int i = lastUser + 1; i < messages.size(); i++) {
            Map<String, Object> message = messages.get(i);
            if ("assistant".equals(message.get("role")) && message.get("tool_calls") instanceof List<?>) {
                steps.add(i);
            }
        }

        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            int distanceFromEnd = steps.size() - stepIndex;
            if (distanceFromEnd <= config.keepRecentToolSteps()) continue;
            int assistantIndex = steps.get(stepIndex);
            foldToolExchange(messages, assistantIndex);
            if (distanceFromEnd > config.summarizeAfterStep()) {
                summarizeAssistantStep(messages.get(assistantIndex), distanceFromEnd);
            }
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static void foldToolExchange(List<Map<String, Object>> messages, int assistantIndex) {
        Map<String, Object> assistant = messages.get(assistantIndex);
        Object rawCalls = assistant.get("tool_calls");
        if (!(rawCalls instanceof List<?> calls)) return;

        Map<String, String> namesById = new LinkedHashMap<>();
        for (Object call : calls) {
            if (!(call instanceof Map<?, ?> callMap)) continue;
            String id = String.valueOf(valueOrDefault(callMap, "id", ""));
            Object functionObject = callMap.get("function");
            String name = "unknown";
            if (functionObject instanceof Map<?, ?> function) {
                name = String.valueOf(valueOrDefault(function, "name", "unknown"));
                ((Map<String, Object>) function).put("arguments", "{}");
            }
            if (!id.isBlank()) namesById.put(id, name);
        }

        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            Map<String, Object> message = messages.get(i);
            if (!"tool".equals(message.get("role"))) break;
            String id = String.valueOf(message.getOrDefault("tool_call_id", ""));
            String name = namesById.get(id);
            if (name != null) {
                message.put("content", FoldedToolReference.render(name, id,
                        String.valueOf(message.getOrDefault("content", ""))));
            }
        }
    }

    private static void summarizeAssistantStep(Map<String, Object> assistant, int distanceFromEnd) {
        String content = String.valueOf(assistant.getOrDefault("content", "")).trim();
        String summary = content.length() <= 240 ? content : content.substring(0, 240) + "...";
        StringBuilder refs = new StringBuilder();
        Object calls = assistant.get("tool_calls");
        if (calls instanceof List<?> list) {
            for (Object call : list) {
                if (call instanceof Map<?, ?> map) {
                    if (refs.length() > 0) refs.append(",");
                    refs.append(valueOrDefault(map, "id", "unknown"));
                }
            }
        }
        assistant.put("content", "[tool-step-summary step=" + distanceFromEnd
                + "] tool_call_ids=" + refs + "\n" + summary);
    }

    private static List<Map<String, Object>> stripHistoricalRounds(List<Map<String, Object>> messages,
                                                                     FoldConfig config) {
        int estimated = estimateChars(messages);
        int rounds = countUserRounds(messages);
        int stripFromEnd = estimated > config.level2BudgetChars() && rounds > 1 ? 1
                : estimated > config.level1BudgetChars() && rounds > 3 ? 3 : 0;
        if (stripFromEnd == 0) return messages;

        List<List<Map<String, Object>>> roundList = splitRounds(messages);
        List<Map<String, Object>> result = new ArrayList<>();
        int keepFrom = Math.max(0, roundList.size() - stripFromEnd);
        for (int i = 0; i < roundList.size(); i++) {
            List<Map<String, Object>> round = roundList.get(i);
            if (i >= keepFrom) {
                result.addAll(round);
                continue;
            }
            Map<String, Object> lastAnswer = null;
            for (Map<String, Object> message : round) {
                if ("user".equals(message.get("role"))) result.add(message);
                if ("assistant".equals(message.get("role")) && message.get("tool_calls") == null) {
                    lastAnswer = message;
                }
            }
            if (lastAnswer != null && !result.contains(lastAnswer)) result.add(lastAnswer);
        }
        return result;
    }

    private static List<List<Map<String, Object>>> splitRounds(List<Map<String, Object>> messages) {
        List<List<Map<String, Object>>> rounds = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if ("user".equals(message.get("role")) && !current.isEmpty()) {
                rounds.add(current);
                current = new ArrayList<>();
            }
            current.add(message);
        }
        if (!current.isEmpty()) rounds.add(current);
        return rounds;
    }

    private static List<Map<String, Object>> capIndividualMessages(List<Map<String, Object>> messages,
                                                                    int maxChars) {
        for (Map<String, Object> message : messages) {
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (content.length() <= maxChars) continue;
            if ("tool".equals(message.get("role"))) {
                String id = String.valueOf(message.getOrDefault("tool_call_id", "unknown"));
                String name = String.valueOf(message.getOrDefault("name", "unknown"));
                message.put("content", FoldedToolReference.render(name, id, content));
            } else {
                message.put("content", foldPlainText(content));
            }
        }
        return messages;
    }

    private static List<Map<String, Object>> trimToFinalBudget(List<Map<String, Object>> messages,
                                                                int triggerChars) {
        if (estimateChars(messages) <= triggerChars) return messages;
        int lastUser = lastIndexOfRole(messages, "user");
        if (lastUser > 0) messages.subList(0, lastUser).clear();
        if (estimateChars(messages) <= triggerChars) return messages;
        for (Map<String, Object> message : messages) {
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (content.length() > 512) message.put("content", foldPlainText(content));
        }
        return messages;
    }

    public static String foldPlainText(String text) {
        if (text == null || text.length() <= LEAF_MIN_CHARS) return text == null ? "" : text;
        return text.substring(0, KEEP_HEAD_CHARS) + "...[agent:folded]..."
                + text.substring(text.length() - KEEP_TAIL_CHARS);
    }

    public static String foldJsonText(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            return JSON.toJSONString(foldJsonNode(JSON.parse(json)));
        } catch (Exception ignored) {
            return foldPlainText(json);
        }
    }

    private static Object foldJsonNode(Object node) {
        if (node instanceof String text) return foldPlainText(text);
        if (node instanceof JSONObject object) {
            JSONObject result = new JSONObject();
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                result.put(entry.getKey(), foldJsonNode(entry.getValue()));
            }
            return result;
        }
        if (node instanceof JSONArray array) {
            JSONArray result = new JSONArray();
            for (Object item : array) result.add(foldJsonNode(item));
            return result;
        }
        return node;
    }

    private static int lastIndexOfRole(List<Map<String, Object>> messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (role.equals(messages.get(i).get("role"))) return i;
        }
        return -1;
    }

    private static int countUserRounds(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> message : messages) if ("user".equals(message.get("role"))) count++;
        return count;
    }

    private static int estimateChars(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content != null) total += String.valueOf(content).length();
        }
        return total;
    }

    private static Object valueOrDefault(Map<?, ?> map, Object key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (source == null) return target;
        source.forEach((key, value) -> target.put(key, copyValue(value)));
        return target;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), copyValue(item)));
            return nested;
        }
        if (value instanceof List<?> list) return list.stream().map(MemoryFoldingPipeline::copyValue).toList();
        return value;
    }
}
