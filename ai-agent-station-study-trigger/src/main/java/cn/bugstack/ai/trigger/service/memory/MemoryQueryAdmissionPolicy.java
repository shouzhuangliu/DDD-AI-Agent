package cn.bugstack.ai.trigger.service.memory;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryQueryAdmissionPolicy {

    private static final Pattern INFORMATIVE_TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z]{4,}|\\d{2,}");
    private static final Pattern NON_WORD = Pattern.compile("^[\\p{Punct}\\s]+$");
    private static final List<String> TRIVIAL_INPUTS = List.of(
            "1", "2", "3", "hi", "hello", "ok", "okay", "yes", "no",
            "你好", "您好", "好的", "收到", "嗯", "哦", "在吗");

    public boolean shouldRecall(String rawQuery) {
        return inspectRecallQuery(rawQuery).allowed();
    }

    public boolean shouldStoreSummary(String rawSummary) {
        return inspectSummary(rawSummary).allowed();
    }

    public AdmissionDecision inspectRecallQuery(String rawQuery) {
        String query = normalize(rawQuery);
        if (query.isBlank()) return new AdmissionDecision(false, "EMPTY", query, 0, 1, 0);
        if (TRIVIAL_INPUTS.contains(query.toLowerCase(Locale.ROOT))) {
            return new AdmissionDecision(false, "TRIVIAL_INPUT", query, informativeTokenCount(query), 1, query.length());
        }
        if (NON_WORD.matcher(query).matches()) {
            return new AdmissionDecision(false, "PUNCTUATION_ONLY", query, 0, 1, query.length());
        }
        if (query.chars().allMatch(Character::isDigit)) {
            return new AdmissionDecision(false, "PURE_NUMBER", query, informativeTokenCount(query), 1, query.length());
        }
        int informativeTokens = informativeTokenCount(query);
        if (informativeTokens < 1) {
            return new AdmissionDecision(false, "LACK_OF_CONTEXT", query, informativeTokens, 1, query.length());
        }
        return new AdmissionDecision(true, "ACCEPTED", query, informativeTokens, 1, query.length());
    }

    public AdmissionDecision inspectSummary(String rawSummary) {
        String summary = normalize(rawSummary);
        if (summary.isBlank()) return new AdmissionDecision(false, "EMPTY", summary, 0, 2, 0);
        if (summary.length() < 20) {
            return new AdmissionDecision(false, "TOO_SHORT", summary, informativeTokenCount(summary), 2, summary.length());
        }
        int informativeTokens = informativeTokenCount(summary);
        if (informativeTokens < 2) {
            return new AdmissionDecision(false, "WEAK_INFORMATION_DENSITY", summary, informativeTokens, 2, summary.length());
        }
        return new AdmissionDecision(true, "ACCEPTED", summary, informativeTokens, 2, summary.length());
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    private int informativeTokenCount(String text) {
        Matcher matcher = INFORMATIVE_TOKEN.matcher(text);
        int informativeTokens = 0;
        while (matcher.find()) informativeTokens++;
        return informativeTokens;
    }

    public record AdmissionDecision(boolean allowed,
                                    String reasonCode,
                                    String normalizedText,
                                    int informativeTokenCount,
                                    int requiredInformativeTokenCount,
                                    int length) {
    }
}
