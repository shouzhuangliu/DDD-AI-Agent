package cn.bugstack.ai.trigger.service.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admission policy for operational-quality analysis. It intentionally keeps
 * casual, test and incomplete conversations out of the Case pipeline.
 */
public class ConversationQualificationPolicy {

    private static final int MIN_USER_TURNS = 2;
    private static final int MIN_USER_CHARACTERS = 36;
    private static final int MIN_TRANSCRIPT_CHARACTERS = 80;
    private static final String INTERNAL_EXECUTION_PLACEHOLDER = "ai agent execution summary completed!";
    private static final double MIN_BUSINESS_RELEVANCE = 70d;
    private static final double MIN_CASE_CONFIDENCE = 75d;
    private static final double MIN_EVIDENCE_SCORE = 60d;
    private static final double MIN_SINGLE_SESSION_BUSINESS_RELEVANCE = 85d;
    private static final double MIN_SINGLE_SESSION_CASE_CONFIDENCE = 80d;
    private static final double MIN_SINGLE_SESSION_EVIDENCE_SCORE = 75d;
    private static final double MIN_CRITICAL_RISK_CONFIDENCE = 90d;
    private static final double MIN_CRITICAL_RISK_EVIDENCE_SCORE = 80d;
    private static final Set<String> BUSINESS_EVIDENCE_SOURCES = Set.of(
            "USER_FEEDBACK", "SKILL_RESULT", "MCP_BUSINESS_DATA", "CASE_HISTORY");
    private static final Set<String> LOW_VALUE_INPUTS = Set.of(
            "1", "ok", "okay", "yes", "y", "no", "n", "hi", "hello",
            "继续", "好的", "好", "可以", "嗯", "啊", "行", "收到", "明白", "测试"
    );

    public boolean shouldAnalyze(List<ConversationMessage> messages, int explicitNegativeFeedback) {
        if (explicitNegativeFeedback > 0) return true;
        int userTurns = 0;
        int userCharacters = 0;
        int meaningfulUserTurns = 0;
        int transcriptCharacters = 0;
        for (ConversationMessage message : messages) {
            String content = normalize(message.content());
            if (content.isEmpty() || isInternalPlaceholder(content)) continue;
            transcriptCharacters += content.length();
            if ("user".equalsIgnoreCase(message.role())) {
                userTurns++;
                userCharacters += content.length();
                if (!isLowValueUserInput(content)) {
                    meaningfulUserTurns++;
                }
            }
        }
        return userTurns >= MIN_USER_TURNS
                && meaningfulUserTurns >= MIN_USER_TURNS
                && userCharacters >= MIN_USER_CHARACTERS
                && transcriptCharacters >= MIN_TRANSCRIPT_CHARACTERS;
    }

    public boolean shouldPromoteCase(int distinctSessions, int explicitNegativeFeedback,
                                     double confidence, boolean criticalRisk) {
        return shouldPromoteCase(new CasePromotionInput(
                distinctSessions, explicitNegativeFeedback, confidence, criticalRisk,
                MIN_BUSINESS_RELEVANCE, MIN_EVIDENCE_SCORE, false));
    }

    public boolean shouldPromoteCase(CasePromotionInput input) {
        if (input == null) return false;
        if (input.businessRelevance() < MIN_BUSINESS_RELEVANCE) return false;
        if (input.evidenceScore() < MIN_EVIDENCE_SCORE) return false;
        if (input.confidence() < MIN_CASE_CONFIDENCE) return false;
        if (input.historicalHighRiskMatch()) return true;
        if (input.criticalRisk()
                && input.confidence() >= MIN_CRITICAL_RISK_CONFIDENCE
                && input.evidenceScore() >= MIN_CRITICAL_RISK_EVIDENCE_SCORE) {
            return true;
        }
        if (input.explicitNegativeFeedback() > 0
                && input.businessRelevance() >= MIN_SINGLE_SESSION_BUSINESS_RELEVANCE
                && input.evidenceScore() >= MIN_SINGLE_SESSION_EVIDENCE_SCORE
                && input.confidence() >= MIN_SINGLE_SESSION_CASE_CONFIDENCE) {
            return true;
        }
        return input.distinctSessions() >= 2;
    }

    public boolean hasBusinessEvidence(AnalysisResultParser.CaseCandidate candidate) {
        if (candidate == null || !candidate.businessEvidence()) return false;
        return BUSINESS_EVIDENCE_SOURCES.contains(candidate.evidenceSource());
    }

    public boolean isInternalPlaceholder(String content) {
        return normalize(content).toLowerCase(Locale.ROOT).contains(INTERNAL_EXECUTION_PLACEHOLDER);
    }

    public boolean isLowValueUserInput(String content) {
        String normalized = normalize(content).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        if (LOW_VALUE_INPUTS.contains(normalized)) return true;
        return normalized.length() <= 2
                && normalized.chars().noneMatch(Character::isLetter)
                && normalized.chars().noneMatch(Character::isIdeographic);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record ConversationMessage(String role, String content) { }

    public record CasePromotionInput(int distinctSessions,
                                     int explicitNegativeFeedback,
                                     double confidence,
                                     boolean criticalRisk,
                                     double businessRelevance,
                                     double evidenceScore,
                                     boolean historicalHighRiskMatch) { }
}
