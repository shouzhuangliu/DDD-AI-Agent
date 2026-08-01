package cn.bugstack.ai.trigger.service.analysis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class AnalysisResultParser {

    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    public AnalysisResult parse(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("```")) {
            throw new IllegalArgumentException("Analysis output must be a plain JSON object");
        }
        final JSONObject root;
        try {
            root = JSON.parseObject(raw);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid analysis JSON", exception);
        }
        if (!root.containsKey("signals") || !root.containsKey("cases")) {
            throw new IllegalArgumentException("Analysis JSON requires signals and cases arrays");
        }
        JSONArray signalArray = root.getJSONArray("signals");
        JSONArray caseArray = root.getJSONArray("cases");
        if (signalArray == null || caseArray == null) {
            throw new IllegalArgumentException("signals and cases must be arrays");
        }
        List<SignalCandidate> signals = new ArrayList<>();
        for (int i = 0; i < signalArray.size(); i++) {
            JSONObject value = signalArray.getJSONObject(i);
            signals.add(new SignalCandidate(
                    required(value, "type").toUpperCase(Locale.ROOT),
                    severity(value), confidence(value), AnalysisTextLocalizer.signalSummary(required(value, "summary")),
                    required(value, "rationale")));
        }
        List<CaseCandidate> cases = new ArrayList<>();
        for (int i = 0; i < caseArray.size(); i++) {
            JSONObject value = caseArray.getJSONObject(i);
            double importance = bounded(value.getDoubleValue("importance"), "importance");
            double businessRelevance = bounded(value.getDoubleValue("businessRelevance"), "businessRelevance");
            double evidenceScore = bounded(value.getDoubleValue("evidenceScore"), "evidenceScore");
            cases.add(new CaseCandidate(AnalysisTextLocalizer.caseTitle(required(value, "title")),
                    AnalysisTextLocalizer.caseSummary(required(value, "summary")),
                    required(value, "caseType").toUpperCase(Locale.ROOT), severity(value), importance,
                    confidence(value), value.getBooleanValue("criticalRisk"),
                    value.getBooleanValue("businessRelated"), businessRelevance, evidenceScore,
                    value.getBooleanValue("promoteToCase"), value.getBooleanValue("historicalHighRiskMatch"),
                    required(value, "reason"), value.getBooleanValue("businessEvidence"),
                    normalizeEvidenceSource(value.getString("evidenceSource"))));
        }
        return new AnalysisResult(List.copyOf(signals), List.copyOf(cases));
    }

    private String required(JSONObject value, String key) {
        String result = value.getString(key);
        if (result == null || result.isBlank()) throw new IllegalArgumentException(key + " is required");
        return result.trim();
    }

    private String severity(JSONObject value) {
        String severity = required(value, "severity").toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) throw new IllegalArgumentException("Unsupported severity " + severity);
        return severity;
    }

    private double confidence(JSONObject value) {
        return bounded(value.getDoubleValue("confidence"), "confidence");
    }

    private double bounded(double value, String field) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be between 0 and 100");
        return value;
    }

    private String normalizeEvidenceSource(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record AnalysisResult(List<SignalCandidate> signals, List<CaseCandidate> cases) {}
    public record SignalCandidate(String type, String severity, double confidence, String summary, String rationale) {}
    public record CaseCandidate(String title, String summary, String caseType, String severity,
                                double importance, double confidence, boolean criticalRisk,
                                boolean businessRelated, double businessRelevance, double evidenceScore,
                                boolean promoteToCase, boolean historicalHighRiskMatch, String reason,
                                boolean businessEvidence, String evidenceSource) {}
}
