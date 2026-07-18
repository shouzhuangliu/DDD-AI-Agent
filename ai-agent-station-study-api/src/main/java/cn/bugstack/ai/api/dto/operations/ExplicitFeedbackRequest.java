package cn.bugstack.ai.api.dto.operations;

import java.util.Locale;
import java.util.Set;

public record ExplicitFeedbackRequest(
        String sessionId,
        Long assistantMessageId,
        String feedbackType,
        Integer rating,
        String message,
        String correction,
        String submittedBy) {

    private static final Set<String> TYPES = Set.of(
            "THUMBS_UP", "THUMBS_DOWN", "RATING", "COMMENT", "CORRECTION", "ISSUE_REPORT");

    public ExplicitFeedbackRequest validate() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (assistantMessageId == null || assistantMessageId <= 0) {
            throw new IllegalArgumentException("assistantMessageId is required");
        }
        String normalizedType = feedbackType == null ? "" : feedbackType.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Unsupported feedbackType: " + feedbackType);
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        if (Set.of("COMMENT", "CORRECTION", "ISSUE_REPORT").contains(normalizedType)
                && (message == null || message.isBlank())
                && (correction == null || correction.isBlank())) {
            throw new IllegalArgumentException("message or correction is required for " + normalizedType);
        }
        return this;
    }

    public String normalizedType() {
        return feedbackType.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizedSubmittedBy() {
        return submittedBy == null || submittedBy.isBlank() ? "anonymous" : submittedBy.trim();
    }
}
