package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 推理前历史折叠的确定性配置。
 */
public record FoldConfig(
        int keepRecentToolSteps,
        int summarizeAfterStep,
        int maxMessageChars,
        int level1BudgetChars,
        int level2BudgetChars,
        int finalTriggerChars) {

    public FoldConfig {
        if (keepRecentToolSteps < 0 || summarizeAfterStep < keepRecentToolSteps
                || maxMessageChars < 1 || level1BudgetChars < 1
                || level2BudgetChars < level1BudgetChars || finalTriggerChars < level2BudgetChars) {
            throw new IllegalArgumentException("invalid history fold configuration");
        }
    }

    public static FoldConfig defaultProfile() {
        return new FoldConfig(6, 12, 20_000, 40_000, 80_000, 120_000);
    }

    public static FoldConfig testProfile() {
        return new FoldConfig(0, 1, 256, 512, 768, 2_048);
    }

    /**
     * 将模型输入 token 预算映射为字符级确定性折叠配置。
     * Token 估算器对中文按字符计数、对拉丁文本按约四字符计数；这里按中文最坏情况 1:1 映射，
     * 让折叠优先发生而不是冒险压穿模型窗口。
     */
    public static FoldConfig fromBudget(ContextBudgetPolicy.BudgetDecision budget) {
        if (budget == null) return defaultProfile();
        int max = Math.max(256, safeChars(budget.effectiveInputTokens()));
        int level1 = Math.min(max, Math.max(128, safeChars(budget.softSummaryThreshold())));
        int level2 = Math.min(max, Math.max(level1, safeChars(budget.hardFoldThreshold())));
        int maxMessage = Math.max(128, Math.min(max, max / 4));
        return new FoldConfig(6, 12, maxMessage, level1, level2, max);
    }

    private static int safeChars(int tokens) {
        // TokenBudgetEstimator 对中文按字符计数，因此采用 1:1 作为最坏情况安全边界。
        long value = Math.max(1L, tokens);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
