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
}
