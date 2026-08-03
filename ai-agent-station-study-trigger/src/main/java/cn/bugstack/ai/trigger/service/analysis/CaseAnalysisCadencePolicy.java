package cn.bugstack.ai.trigger.service.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Determines whether a conversation has accumulated enough new business
 * evidence to justify another quality evaluation. This is deliberately
 * deterministic; the LLM is not involved in scheduling decisions.
 */
public class CaseAnalysisCadencePolicy {

    private static final int MIN_NEW_EVIDENCE_MESSAGES = 2;
    private static final String INTERNAL_PLACEHOLDER = "ai agent execution summary completed!";
    private static final Set<String> LOW_VALUE = Set.of(
            "1", "ok", "okay", "yes", "y", "no", "n", "hi", "hello", "继续", "好的", "可以", "收到", "测试"
    );

    public Decision shouldEvaluate(List<ConversationMessage> messages,
                                   EvaluationCursor cursor,
                                   boolean explicitFeedback,
                                   boolean newMcpEvidence) {
        List<ConversationMessage> safeMessages = messages == null ? List.of() : messages;
        EvaluationCursor safeCursor = cursor == null ? new EvaluationCursor(0, 0, "") : cursor;
        List<ConversationMessage> newEvidence = safeMessages.stream()
                .filter(message -> message != null && message.id() > safeCursor.lastEvaluatedMessageId())
                .filter(this::isMeaningfulEvidence)
                .toList();
        String fingerprint = fingerprint(newEvidence);
        if (fingerprint.equals(safeCursor.evidenceFingerprint()) && !explicitFeedback && !newMcpEvidence) {
            return new Decision(false, "证据指纹未变化，跳过重复评测", fingerprint, newEvidence.size());
        }
        if (explicitFeedback || newMcpEvidence) {
            return new Decision(!newEvidence.isEmpty() || explicitFeedback || newMcpEvidence,
                    explicitFeedback ? "收到明确人工 Feedback，允许提前评测" : "收到新的业务 MCP 结果，允许提前评测",
                    fingerprint, newEvidence.size());
        }
        if (newEvidence.size() < MIN_NEW_EVIDENCE_MESSAGES) {
            return new Decision(false, "新增业务证据少于 2 条，等待会话补充", fingerprint, newEvidence.size());
        }
        return new Decision(true, "新增业务证据达到 2 条", fingerprint, newEvidence.size());
    }

    public int countMeaningfulUserTurns(List<ConversationMessage> messages) {
        if (messages == null) return 0;
        return (int) messages.stream().filter(this::isMeaningfulEvidence).count();
    }

    private boolean isMeaningfulEvidence(ConversationMessage message) {
        if (message == null || message.content() == null) return false;
        if (!"user".equalsIgnoreCase(message.role()) && !"operator".equalsIgnoreCase(message.role())) return false;
        String text = normalize(message.content());
        if (text.isBlank() || text.toLowerCase(Locale.ROOT).contains(INTERNAL_PLACEHOLDER)) return false;
        if (LOW_VALUE.contains(text.toLowerCase(Locale.ROOT))) return false;
        return text.length() >= 6;
    }

    private String fingerprint(List<ConversationMessage> messages) {
        String payload = messages.stream()
                .sorted(java.util.Comparator.comparingLong(ConversationMessage::id))
                .map(message -> message.id() + "|" + message.role() + "|" + normalize(message.content()))
                .collect(Collectors.joining("\n"));
        if (payload.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算评测证据指纹", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record ConversationMessage(long id, String role, String content) { }

    public record EvaluationCursor(long lastEvaluatedMessageId,
                                   int lastMeaningfulUserTurns,
                                   String evidenceFingerprint) { }

    public record Decision(boolean required, String reason, String evidenceFingerprint,
                           int newEvidenceCount) { }
}
