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
        String query = normalize(rawQuery);
        if (query.isBlank()) return false;
        if (TRIVIAL_INPUTS.contains(query.toLowerCase(Locale.ROOT))) return false;
        if (NON_WORD.matcher(query).matches()) return false;
        if (query.chars().allMatch(Character::isDigit)) return false;

        Matcher matcher = INFORMATIVE_TOKEN.matcher(query);
        int informativeTokens = 0;
        while (matcher.find()) {
            informativeTokens++;
            if (informativeTokens >= 1) return true;
        }
        return false;
    }

    public boolean shouldStoreSummary(String rawSummary) {
        String summary = normalize(rawSummary);
        if (summary.length() < 20) return false;
        Matcher matcher = INFORMATIVE_TOKEN.matcher(summary);
        int informativeTokens = 0;
        while (matcher.find()) {
            informativeTokens++;
            if (informativeTokens >= 2) return true;
        }
        return false;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }
}
