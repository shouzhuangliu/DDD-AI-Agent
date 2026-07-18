package cn.bugstack.ai.domain.agent.service.memory;

import java.util.List;

public class RollingSummaryPolicy {

    private final TokenBudgetEstimator estimator;
    private final int tokenBudget;
    private final int retainRecentMessages;

    public RollingSummaryPolicy(TokenBudgetEstimator estimator, int tokenBudget, int retainRecentMessages) {
        if (tokenBudget <= 0 || retainRecentMessages < 1) throw new IllegalArgumentException("Invalid memory policy");
        this.estimator = estimator;
        this.tokenBudget = tokenBudget;
        this.retainRecentMessages = retainRecentMessages;
    }

    public SummaryPlan plan(List<MemoryMessage> messages, long lastCoveredMessageId) {
        List<MemoryMessage> uncovered = messages.stream()
                .filter(message -> message.id() > lastCoveredMessageId).toList();
        int tokens = uncovered.stream().mapToInt(message -> estimator.estimate(message.content())).sum();
        if (tokens <= tokenBudget || uncovered.size() <= retainRecentMessages) return SummaryPlan.notRequired(tokens);
        int summaryEndIndex = uncovered.size() - retainRecentMessages - 1;
        MemoryMessage first = uncovered.get(0);
        MemoryMessage lastSummary = uncovered.get(summaryEndIndex);
        MemoryMessage firstRecent = uncovered.get(summaryEndIndex + 1);
        return new SummaryPlan(true, first.id(), lastSummary.id(), firstRecent.id(), tokens);
    }

    public record MemoryMessage(long id, String role, String content) {}

    public record SummaryPlan(boolean required, long startMessageId, long endMessageId,
                              long recentStartMessageId, int estimatedTokens) {
        public static SummaryPlan notRequired(int tokens) { return new SummaryPlan(false, 0, 0, 0, tokens); }
    }
}
