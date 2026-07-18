package cn.bugstack.ai.domain.agent.service.operations;

/** Computes the explainable 0-100 operational priority score for a Case. */
public class CaseScoringService {

    private static final double CRITICAL_PRIORITY_FLOOR = 85d;
    private static final double RESOLVED_DECAY = 0.65d;

    public CaseScoreBreakdown score(CaseScoreInput input) {
        double severity = normalized(input.severity()) * 0.25d;
        double negativeFeedback = normalized(input.negativeFeedback()) * 0.20d;
        double frequency = normalized(input.frequency()) * 0.15d;
        double importance = normalized(input.importance()) * 0.15d;
        double recency = normalized(input.recency()) * 0.10d;
        double unresolvedAge = normalized(input.unresolvedAge()) * 0.10d;
        double confidence = normalized(input.confidence()) * 0.05d;

        double total = severity + negativeFeedback + frequency + importance
                + recency + unresolvedAge + confidence;
        if (input.resolved()) {
            total *= RESOLVED_DECAY;
        }
        boolean floorApplied = input.criticalRisk() && !input.resolved() && total < CRITICAL_PRIORITY_FLOOR;
        if (floorApplied) {
            total = CRITICAL_PRIORITY_FLOOR;
        }

        return new CaseScoreBreakdown(round(total), round(severity), round(negativeFeedback),
                round(frequency), round(importance), round(recency), round(unresolvedAge),
                round(confidence), floorApplied);
    }

    private double normalized(double value) {
        return Math.max(0d, Math.min(100d, value));
    }

    private double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    public record CaseScoreInput(
            double severity,
            double negativeFeedback,
            double frequency,
            double importance,
            double recency,
            double unresolvedAge,
            double confidence,
            boolean criticalRisk,
            boolean resolved) {
    }

    public record CaseScoreBreakdown(
            double total,
            double severityContribution,
            double negativeFeedbackContribution,
            double frequencyContribution,
            double importanceContribution,
            double recencyContribution,
            double unresolvedAgeContribution,
            double confidenceContribution,
            boolean priorityFloorApplied) {
    }
}
