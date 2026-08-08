package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 生成稳定的工具结果取回指针。指针中的 ID 来自平台记录，不来自模型输出。
 */
public final class FoldedToolReference {

    private FoldedToolReference() {
    }

    public static String render(String toolName, String toolCallId, String content) {
        String safeTool = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        String safeId = toolCallId == null || toolCallId.isBlank() ? "unknown" : toolCallId;
        String hint = criticalHint(content);
        String preview = MemoryFoldingPipeline.foldPlainText(content == null ? "" : content);
        String suffix = hint.isBlank() ? "" : " | " + hint;
        return "[tool-result-folded] tool=" + safeTool
                + " | retrieve via retrieve_tool_call(\"" + safeId + "\")"
                + suffix + "\n" + preview;
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
