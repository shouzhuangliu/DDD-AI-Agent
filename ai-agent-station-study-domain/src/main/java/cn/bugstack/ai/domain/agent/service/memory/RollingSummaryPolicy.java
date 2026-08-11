package cn.bugstack.ai.domain.agent.service.memory;

import java.util.List;

public class RollingSummaryPolicy {

    private final TokenBudgetEstimator estimator;
    private final int tokenThreshold;
    private final int hardTokenLimit;
    private final int retainRecentMessages;
    private final int minNewMeaningfulUserTurns;

    public RollingSummaryPolicy(TokenBudgetEstimator estimator, int tokenBudget, int retainRecentMessages) {
        this(estimator, tokenBudget, tokenBudget * 2, retainRecentMessages, 0);
    }

    public RollingSummaryPolicy(TokenBudgetEstimator estimator, int tokenThreshold,
                                int retainRecentMessages, int minNewMeaningfulUserTurns) {
        this(estimator, tokenThreshold, tokenThreshold * 2, retainRecentMessages,
                minNewMeaningfulUserTurns);
    }

    public RollingSummaryPolicy(TokenBudgetEstimator estimator, int tokenThreshold, int hardTokenLimit,
                                int retainRecentMessages, int minNewMeaningfulUserTurns) {
        if (tokenThreshold <= 0 || hardTokenLimit < tokenThreshold || retainRecentMessages < 1
                || minNewMeaningfulUserTurns < 0) {
            throw new IllegalArgumentException("Invalid memory policy");
        }
        this.estimator = estimator;
        this.tokenThreshold = tokenThreshold;
        this.hardTokenLimit = hardTokenLimit;
        this.retainRecentMessages = retainRecentMessages;
        this.minNewMeaningfulUserTurns = minNewMeaningfulUserTurns;
    }

    public SummaryPlan plan(List<MemoryMessage> messages, long lastCoveredMessageId) {
        List<MemoryMessage> uncovered = messages.stream()
                .filter(message -> message.id() > lastCoveredMessageId).toList();
        int tokens = uncovered.stream().mapToInt(message -> estimator.estimate(message.content())).sum();
        boolean hardLimitReached = tokens >= hardTokenLimit;
        int recentCount = Math.min(retainRecentMessages, Math.max(0, uncovered.size() - 1));
        if (recentCount == 0) return SummaryPlan.notRequired(tokens);
        int meaningfulUserTurns = (int) uncovered.stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()) || "operator".equalsIgnoreCase(message.role()))
                .map(MemoryMessage::content)
                .filter(content -> content != null && !content.isBlank())
                .filter(content -> content.trim().length() > 2)
                .count();
        if (!hardLimitReached && (tokens <= tokenThreshold || meaningfulUserTurns < minNewMeaningfulUserTurns)) {
            return SummaryPlan.notRequired(tokens);
        }
        int summaryEndIndex = uncovered.size() - recentCount - 1;
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
