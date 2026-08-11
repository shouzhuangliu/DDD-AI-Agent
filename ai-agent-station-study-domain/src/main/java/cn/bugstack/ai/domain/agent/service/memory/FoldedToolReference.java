package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 生成稳定的工具结果取回指针。指针中的 ID 来自平台记录，不来自模型输出。
 */
public final class FoldedToolReference {

    private FoldedToolReference() {
    }

    public static String render(String toolName, String toolCallId, String content) {
        return render(toolName, toolCallId, content, 200, 100);
    }

    public static String render(String toolName, String toolCallId, String content,
                                int previewHeadChars, int previewTailChars) {
        String safeTool = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        String safeId = toolCallId == null || toolCallId.isBlank() ? "unknown" : toolCallId;
        String hint = criticalHint(content);
        String preview = preview(content == null ? "" : content, previewHeadChars, previewTailChars);
        String suffix = hint.isBlank() ? "" : " | " + hint;
        return "[历史工具结果，已从当前上下文折叠；如需最新数据请重新调用原工具] tool=" + safeTool
                + " | tool_call_id=" + safeId
                + " | 完整结果请调用 retrieve_tool_call(\"" + safeId + "\")"
                + suffix + "\n" + preview;
    }

    private static String preview(String content, int headChars, int tailChars) {
        int safeHead = Math.max(1, headChars);
        int safeTail = Math.max(0, tailChars);
        if (content.length() <= safeHead + safeTail + 32) return content;
        return content.substring(0, safeHead) + "...[agent:tool-result-folded]..."
                + (safeTail == 0 ? "" : content.substring(content.length() - safeTail));
    }

    private static String criticalHint(String content) {
        if (content == null || content.isBlank()) return "";
        for (String line : content.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("ERROR:") || value.startsWith("/data/") || value.contains("sessions/")) {
                return value.length() > 180 ? value.substring(0, 180) : value;
            }
        }
        return "";
    }
}
