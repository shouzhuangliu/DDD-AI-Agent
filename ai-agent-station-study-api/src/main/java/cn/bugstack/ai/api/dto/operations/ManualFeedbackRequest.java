package cn.bugstack.ai.api.dto.operations;

import java.util.Locale;
import java.util.Set;

public record ManualFeedbackRequest(
        String sourceType,
        String feedbackType,
        Integer rating,
        String message,
        String category,
        String submittedBy) {

    private static final Set<String> SOURCE_TYPES = Set.of("USER", "OPERATIONS", "TEST");
    private static final Set<String> FEEDBACK_TYPES = Set.of(
            "THUMBS_UP", "THUMBS_DOWN", "RATING", "COMMENT", "CORRECTION", "ISSUE_REPORT", "NEGATIVE", "POSITIVE");

    public ManualFeedbackRequest validate() {
        if (!SOURCE_TYPES.contains(normalizedSourceType())) {
            throw new IllegalArgumentException("Unsupported sourceType: " + sourceType);
        }
        if (!FEEDBACK_TYPES.contains(normalizedFeedbackType())) {
            throw new IllegalArgumentException("Unsupported feedbackType: " + feedbackType);
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        return this;
    }

    public String normalizedSourceType() {
        return sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizedFeedbackType() {
        return feedbackType == null ? "COMMENT" : feedbackType.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizedSubmittedBy() {
        return submittedBy == null || submittedBy.isBlank() ? "anonymous" : submittedBy.trim();
    }

    public String normalizedCategory() {
        return category == null ? "" : category.trim();
    }

    public String normalizedMessage() {
        return message == null ? "" : message.trim();
    }
}
