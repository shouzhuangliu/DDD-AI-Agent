package cn.bugstack.ai.domain.agent.service.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 记忆折叠管线 — 推理前对消息副本做多级递进压缩。
 * <p>
 * 全部 Java 代码确定性完成，零 LLM 调用。
 * 压缩只改内存副本，不写 DB。
 * <p>
 * 管线顺序: sanitize → 轮内折叠 → 轮外剥离 → 单条截断 → 最终删条
 *
 * @author ai-agent-station-study
 */
@Slf4j
public class MemoryFoldingPipeline {

    public static final int KEEP_RECENT_STEPS = 6;
    public static final int FOLD_TOOL_CONTENT_AFTER_STEP = 6;
    public static final int SUMMARIZE_AFTER_STEP = 12;
    public static final int LEVEL1_BUDGET_CHARS = 40000;
    public static final int LEVEL2_BUDGET_CHARS = 80000;
    public static final int MAX_MESSAGE_CHARS = 20000;
    public static final int STAGE3_TRIGGER_CHARS = 120000;
    public static final int MIN_KEEP_TOOLS = 6;

    private static final String FOLD_MARKER = "……[agent:folded]……";
    private static final int LEAF_MIN_CHARS = 300;
    private static final int KEEP_HEAD_CHARS = 200;
    private static final int KEEP_TAIL_CHARS = 100;

    /** 完整管线入口 */
    public static List<Map<String, Object>> fold(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        // 调用方可能使用 Map.of 构造消息；折叠阶段会修改 content/tool_calls，必须复制为可变 Map。
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            msgs.add(message == null ? new LinkedHashMap<>() : new LinkedHashMap<>(message));
        }

        msgs = sanitize(msgs);
        msgs = intraRoundFold(msgs);
        msgs = sanitize(msgs);
        msgs = interRoundStrip(msgs);
        msgs = sanitize(msgs);
        msgs = capIndividualMessageSizes(msgs);
        msgs = finalTrim(msgs);
        return sanitize(msgs);
    }

    /** sanitize: 配对校验 */
    public static List<Map<String, Object>> sanitize(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> pendingIds = null;
        int pendingAsstIdx = -1;

        for (Map<String, Object> m : messages) {
            String role = (String) m.get("role");
            if ("tool".equals(role)) {
                String tid = (String) m.get("tool_call_id");
                if (pendingIds != null && tid != null && pendingIds.remove(tid)) {
                    out.add(m);
                }
                continue;
            }
            if (pendingIds != null && !pendingIds.isEmpty()) {
                Map<String, Object> lastAsst = out.get(pendingAsstIdx);
                if (lastAsst != null) lastAsst.remove("tool_calls");
                pendingIds = null;
            }
            if ("assistant".equals(role)) {
                Object tc = m.get("tool_calls");
                if (tc instanceof List && !((List<?>) tc).isEmpty()) {
                    pendingIds = new HashSet<>();
                    for (Object tco : (List<?>) tc) {
                        if (tco instanceof Map) {
                            Object id = ((Map<?, ?>) tco).get("id");
                            if (id != null) pendingIds.add(id.toString());
                        }
                    }
                    pendingAsstIdx = out.size();
                } else { pendingIds = null; }
            } else { pendingIds = null; }
            out.add(m);
        }
        if (pendingIds != null && !pendingIds.isEmpty() && pendingAsstIdx >= 0 && pendingAsstIdx < out.size()) {
            Map<String, Object> lastAsst = out.get(pendingAsstIdx);
            if (lastAsst != null) lastAsst.remove("tool_calls");
        }
        return out;
    }

    /** 轮内折叠 */
    private static List<Map<String, Object>> intraRoundFold(List<Map<String, Object>> messages) {
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) { lastUserIdx = i; break; }
        }
        if (lastUserIdx < 0) return messages;
        List<Integer> asstSteps = new ArrayList<>();
        for (int i = lastUserIdx + 1; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);
            if ("assistant".equals(m.get("role")) && m.get("tool_calls") != null) asstSteps.add(i);
        }
        int totalSteps = asstSteps.size();
        for (int si = 0; si < totalSteps; si++) {
            int stepFromEnd = totalSteps - si;
            int idx = asstSteps.get(si);
            if (stepFromEnd <= KEEP_RECENT_STEPS) continue;
            Map<String, Object> asst = messages.get(idx);
            foldToolContent(asst);
            if (stepFromEnd > SUMMARIZE_AFTER_STEP) summarizeStep(asst, stepFromEnd);
        }
        return messages;
    }

    private static void foldToolContent(Map<String, Object> asst) {
        Object tc = asst.get("tool_calls");
        if (tc instanceof List) {
            for (Object tco : (List<?>) tc) {
                if (tco instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> func = (Map<String, Object>) ((Map<String, Object>) tco).get("function");
                    if (func != null) func.put("arguments", "{}");
                }
            }
        }
    }

    private static void summarizeStep(Map<String, Object> asst, int stepFromEnd) {
        String orig = (String) asst.getOrDefault("content", "");
        asst.put("content", orig.length() > 100
                ? "[tool-step-summary step=" + stepFromEnd + "]\n" + orig.substring(0, Math.min(100, orig.length()))
                : orig);
    }

    /** 轮外剥离 */
    private static List<Map<String, Object>> interRoundStrip(List<Map<String, Object>> messages) {
        int est = estimateChars(messages);
        int userRounds = countUserRounds(messages);
        if (userRounds <= 1) return messages;
        int stripFromEnd = 0;
        if (est > LEVEL2_BUDGET_CHARS && userRounds > 1) stripFromEnd = 1;
        else if (est > LEVEL1_BUDGET_CHARS && userRounds > 3) stripFromEnd = 3;
        if (stripFromEnd <= 0) return messages;
        return stripOldRounds(messages, stripFromEnd);
    }

    private static List<Map<String, Object>> stripOldRounds(List<Map<String, Object>> messages, int stripFromEnd) {
        List<List<Map<String, Object>>> roundsList = new ArrayList<>();
        List<Map<String, Object>> currentRound = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            currentRound.add(m);
            if ("user".equals(m.get("role"))) {
                if (!currentRound.isEmpty()) { roundsList.add(currentRound); currentRound = new ArrayList<>(); }
            }
        }
        if (!currentRound.isEmpty()) roundsList.add(currentRound);
        List<Map<String, Object>> result = new ArrayList<>();
        int totalRounds = roundsList.size();
        for (int ri = 0; ri < totalRounds; ri++) {
            if (ri < totalRounds - stripFromEnd) {
                Map<String, Object> lastAsst = null;
                for (Map<String, Object> m : roundsList.get(ri)) {
                    if ("assistant".equals(m.get("role")) && m.get("tool_calls") == null) lastAsst = m;
                }
                for (Map<String, Object> m : roundsList.get(ri)) {
                    if ("user".equals(m.get("role"))) result.add(m);
                }
                if (lastAsst != null) result.add(lastAsst);
            } else {
                result.addAll(roundsList.get(ri));
            }
        }
        return result;
    }

    /** 单条截断 */
    private static List<Map<String, Object>> capIndividualMessageSizes(List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            String content = (String) m.get("content");
            if (content != null && content.length() > MAX_MESSAGE_CHARS) {
                m.put("content", content.substring(0, MAX_MESSAGE_CHARS) + "\n……[truncated]……");
            }
        }
        return messages;
    }

    /** 最终删条 */
    private static List<Map<String, Object>> finalTrim(List<Map<String, Object>> messages) {
        int est = estimateChars(messages);
        if (est <= STAGE3_TRIGGER_CHARS) return messages;
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) { lastUserIdx = i; break; }
        }
        if (lastUserIdx > 0) messages.subList(0, lastUserIdx).clear();
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("role", "assistant");
        notice.put("content", "上下文已裁剪，完整正文仍在磁盘——需要时可用 read_file 取回。已完成的工具调用请勿重复执行。");
        messages.add(0, notice);
        return messages;
    }

    private static int estimateChars(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> m : messages) {
            String c = (String) m.get("content");
            if (c != null) total += c.length();
        }
        return total;
    }

    private static int countUserRounds(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> m : messages) { if ("user".equals(m.get("role"))) count++; }
        return count;
    }

    public static String foldPlainText(String text) {
        if (text == null || text.length() <= LEAF_MIN_CHARS) return text;
        return text.substring(0, KEEP_HEAD_CHARS) + FOLD_MARKER + text.substring(text.length() - KEEP_TAIL_CHARS);
    }

    public static String foldJsonText(String json) {
        if (json == null || json.isBlank()) return json;
        try { return JSON.toJSONString(foldJsonNode(JSON.parse(json))); }
        catch (Exception e) { return foldPlainText(json); }
    }

    @SuppressWarnings("unchecked")
    private static Object foldJsonNode(Object node) {
        if (node instanceof String s) return foldPlainText(s);
        if (node instanceof JSONObject obj) {
            JSONObject r = new JSONObject();
            for (Map.Entry<String, Object> e : obj.entrySet()) r.put(e.getKey(), foldJsonNode(e.getValue()));
            return r;
        }
        if (node instanceof JSONArray arr) {
            JSONArray r = new JSONArray();
            for (Object item : arr) r.add(foldJsonNode(item));
            return r;
        }
        return node;
    }
}
