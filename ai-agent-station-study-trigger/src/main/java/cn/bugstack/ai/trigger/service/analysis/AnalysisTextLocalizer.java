package cn.bugstack.ai.trigger.service.analysis;

import java.util.Locale;
import java.util.Map;

final class AnalysisTextLocalizer {

    private static final Map<String, String> CASE_TITLES = Map.of(
            "irrelevant response to user query", "答非所问",
            "inappropriate response to greeting", "问候回复不当",
            "ambiguous input handling", "模糊输入处理不当",
            "generic execution summary", "泛化执行总结外露"
    );

    private static final Map<String, String> CASE_SUMMARIES = Map.of(
            "assistant answered with a generic execution summary instead of addressing the user's query",
            "答复没有回应用户问题，而是返回了泛化或内部执行总结。"
    );

    private static final Map<String, String> SIGNAL_SUMMARIES = Map.of(
            "assistant responded with a generic execution summary",
            "助手返回了泛化或内部执行总结，没有直接回应用户问题。"
    );

    private AnalysisTextLocalizer() {
    }

    static String caseTitle(String value) {
        return localize(value, CASE_TITLES);
    }

    static String caseSummary(String value) {
        return localize(value, CASE_SUMMARIES);
    }

    static String signalSummary(String value) {
        return localize(value, SIGNAL_SUMMARIES);
    }

    private static String localize(String value, Map<String, String> dictionary) {
        if (value == null) return "";
        String trimmed = value.trim();
        return dictionary.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }
}
