package cn.bugstack.ai.trigger.service.analysis;

import java.util.List;
import java.util.Locale;

/**
 * Admission policy for operational-quality analysis. It intentionally keeps
 * casual, test and incomplete conversations out of the Case pipeline.
 */
public class ConversationQualificationPolicy {

    private static final int MIN_USER_TURNS = 2;
    private static final int MIN_USER_CHARACTERS = 36;
    private static final int MIN_TRANSCRIPT_CHARACTERS = 80;
    private static final String INTERNAL_EXECUTION_PLACEHOLDER = "ai agent execution summary completed!";

    public boolean shouldAnalyze(List<ConversationMessage> messages, int explicitNegativeFeedback) {
        if (explicitNegativeFeedback > 0) return true;
        int userTurns = 0;
        int userCharacters = 0;
        int transcriptCharacters = 0;
        for (ConversationMessage message : messages) {
            String content = normalize(message.content());
            if (content.isEmpty() || isInternalPlaceholder(content)) continue;
            transcriptCharacters += content.length();
            if ("user".equalsIgnoreCase(message.role())) {
                userTurns++;
                userCharacters += content.length();
            }
        }
        return userTurns >= MIN_USER_TURNS
                && userCharacters >= MIN_USER_CHARACTERS
                && transcriptCharacters >= MIN_TRANSCRIPT_CHARACTERS;
    }

    public boolean shouldPromoteCase(int distinctSessions, int explicitNegativeFeedback,
                                     double confidence, boolean criticalRisk) {
        if (criticalRisk && confidence >= 85) return true;
        if (explicitNegativeFeedback > 0 && confidence >= 60) return true;
        return distinctSessions >= 2 && confidence >= 60;
    }

    public boolean isInternalPlaceholder(String content) {
        return normalize(content).toLowerCase(Locale.ROOT).contains(INTERNAL_EXECUTION_PLACEHOLDER);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record ConversationMessage(String role, String content) { }
}
