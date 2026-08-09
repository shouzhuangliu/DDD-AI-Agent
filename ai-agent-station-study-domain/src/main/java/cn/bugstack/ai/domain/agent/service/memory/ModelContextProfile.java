package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 模型上下文窗口的运行时约束。窗口大小不是折叠策略本身，策略会在此基础上扣除输出预留和安全边界。
 */
public record ModelContextProfile(
        int contextWindowTokens,
        int maxOutputTokens,
        double softSummaryRatio,
        double hardFoldRatio,
        int safetyMarginTokens) {

    public ModelContextProfile {
        if (contextWindowTokens < 256 || maxOutputTokens < 0 || maxOutputTokens >= contextWindowTokens
                || softSummaryRatio <= 0 || softSummaryRatio >= 1
                || hardFoldRatio <= softSummaryRatio || hardFoldRatio >= 1
                || safetyMarginTokens < 0) {
            throw new IllegalArgumentException("invalid model context profile");
        }
    }

    public static ModelContextProfile safeDefault() {
        return new ModelContextProfile(32_768, 4_096, 0.60d, 0.85d, 1_024);
    }
}
