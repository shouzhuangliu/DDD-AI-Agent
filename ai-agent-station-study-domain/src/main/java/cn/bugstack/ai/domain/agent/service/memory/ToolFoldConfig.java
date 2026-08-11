package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 工具结果专用的折叠配置。工具结果不能复用普通消息的折叠规则，
 * 因为它需要保留 tool_call_id，并明确这是历史结果而不是实时数据。
 */
public record ToolFoldConfig(
        int maxResultChars,
        int previewHeadChars,
        int previewTailChars) {

    public ToolFoldConfig {
        if (maxResultChars < 1 || previewHeadChars < 1 || previewTailChars < 0
                || previewHeadChars + previewTailChars >= maxResultChars) {
            throw new IllegalArgumentException("invalid tool fold configuration");
        }
    }

    public static ToolFoldConfig defaultProfile() {
        return new ToolFoldConfig(20_000, 200, 100);
    }

    public static ToolFoldConfig testProfile() {
        return new ToolFoldConfig(64, 24, 16);
    }

    public static ToolFoldConfig from(FoldConfig config) {
        if (config == null) return defaultProfile();
        int max = Math.max(256, config.maxMessageChars());
        int head = Math.min(200, Math.max(24, max / 8));
        int tail = Math.min(100, Math.max(0, max / 16));
        if (head + tail >= max) tail = 0;
        return new ToolFoldConfig(max, head, tail);
    }
}
