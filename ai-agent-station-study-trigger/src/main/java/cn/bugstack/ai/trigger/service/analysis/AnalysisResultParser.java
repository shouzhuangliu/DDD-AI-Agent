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
    private static final Set<String> RUNTIME_ONLY_SIGNAL_TYPES = Set.of(
            "TOOL_FAILURE", "MCP_FAILURE", "MODEL_FAILURE", "MODEL_RATE_LIMIT", "EXECUTION_FAILURE");

    public static boolean isRuntimeOnlySignalType(String type) {
        return type != null && RUNTIME_ONLY_SIGNAL_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

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
        if (root.containsKey("decision")) return parseStructured(root);
        return parseLegacy(root);
    }

    private AnalysisResult parseStructured(JSONObject root) {
        String decision = required(root, "decision").toUpperCase(Locale.ROOT);
        if (!Set.of("NOT_ELIGIBLE", "FEEDBACK_ONLY", "NEED_MORE_INFO", "CANDIDATE_CASE").contains(decision)) {
            throw new IllegalArgumentException("Unsupported decision " + decision);
        }
        JSONObject skillValue = root.getJSONObject("skill");
        SkillMatch skill = skillValue == null ? SkillMatch.empty() : new SkillMatch(
                value(skillValue, "id"), strings(skillValue.getJSONArray("ruleIds")),
                bounded(skillValue.getDoubleValue("matchScore"), "skill.matchScore"));
        JSONObject factsValue = root.getJSONObject("facts");
        FactSet facts = factsValue == null ? FactSet.empty() : new FactSet(
                value(factsValue, "subject"), value(factsValue, "expected"), value(factsValue, "actual"),
                value(factsValue, "impact"), value(factsValue, "timeRange"), value(factsValue, "scope"));
        List<EvidenceCandidate> evidence = parseEvidence(root.getJSONArray("evidence"));
        List<String> missing = strings(root.getJSONArray("missingInformation"));
        if ("NOT_ELIGIBLE".equals(decision) && !evidence.isEmpty()) {
            throw new IllegalArgumentException("NOT_ELIGIBLE cannot contain evidence");
        }
        if ("CANDIDATE_CASE".equals(decision) && (skill.id().isBlank() || skill.ruleIds().isEmpty())) {
            throw new IllegalArgumentException("CANDIDATE_CASE requires skill id and ruleIds");
        }
        return new AnalysisResult(decision, skill, facts, evidence, missing,
                structuredSeverity(root), confidence(root), required(root, "reason"),
                parseSignals(root.getJSONArray("signals")), List.of());
    }

    private AnalysisResult parseLegacy(JSONObject root) {
        if (!root.containsKey("signals") || !root.containsKey("cases")) {
            throw new IllegalArgumentException("Analysis JSON requires decision or signals and cases arrays");
        }
        JSONArray signalArray = root.getJSONArray("signals");
        JSONArray caseArray = root.getJSONArray("cases");
        if (signalArray == null || caseArray == null) {
            throw new IllegalArgumentException("signals and cases must be arrays");
        }
        List<SignalCandidate> signals = parseSignals(signalArray);
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
                    normalizeEvidenceSource(value.getString("evidenceSource")),
                     value.getString("skillId")));
        }
        return new AnalysisResult("LEGACY_UNVERIFIED", SkillMatch.empty(), FactSet.empty(), List.of(), List.of(),
                "", 0, "legacy analysis contract", List.copyOf(signals), List.copyOf(cases));
    }

    private List<SignalCandidate> parseSignals(JSONArray signalArray) {
        if (signalArray == null) return List.of();
        List<SignalCandidate> signals = new ArrayList<>();
        for (int i = 0; i < signalArray.size(); i++) {
            JSONObject value = signalArray.getJSONObject(i);
            signals.add(new SignalCandidate(
                    required(value, "type").toUpperCase(Locale.ROOT),
                    severity(value), confidence(value), AnalysisTextLocalizer.signalSummary(required(value, "summary")),
                    required(value, "rationale")));
        }
        return List.copyOf(signals);
    }

    private List<EvidenceCandidate> parseEvidence(JSONArray evidenceArray) {
        if (evidenceArray == null) return List.of();
        List<EvidenceCandidate> evidence = new ArrayList<>();
        for (int i = 0; i < evidenceArray.size(); i++) {
            JSONObject value = evidenceArray.getJSONObject(i);
            long messageId = value.getLongValue("messageId");
            if (messageId <= 0) throw new IllegalArgumentException("evidence.messageId must be positive");
            String role = required(value, "role").toLowerCase(Locale.ROOT);
            if (!Set.of("user", "operator", "tool", "assistant").contains(role)) {
                throw new IllegalArgumentException("Unsupported evidence role " + role);
            }
            String quote = required(value, "quote");
            if (quote.length() > 500) throw new IllegalArgumentException("evidence.quote must be <= 500 chars");
            evidence.add(new EvidenceCandidate(messageId, role, quote, strings(value.getJSONArray("supports"))));
        }
        return List.copyOf(evidence);
    }

    private String required(JSONObject value, String key) {
        String result = value.getString(key);
        if (result == null || result.isBlank()) throw new IllegalArgumentException(key + " is required");
        return result.trim();
    }

    private String value(JSONObject value, String key) {
        String result = value == null ? null : value.getString(key);
        return result == null ? "" : result.trim();
    }

    private List<String> strings(JSONArray values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) result.add(String.valueOf(value).trim());
        }
        return List.copyOf(result);
    }

    private String structuredSeverity(JSONObject value) {
        String severity = required(value, "severity").toUpperCase(Locale.ROOT);
        if (!Set.of("P0", "P1", "P2", "P3").contains(severity)) {
            throw new IllegalArgumentException("Unsupported structured severity " + severity);
        }
        return severity;
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

    public record AnalysisResult(String decision, SkillMatch skill, FactSet facts,
                                 List<EvidenceCandidate> evidence, List<String> missingInformation,
                                 String severity, double confidence, String reason,
                                 List<SignalCandidate> signals, List<CaseCandidate> cases) {
        public AnalysisResult(List<SignalCandidate> signals, List<CaseCandidate> cases) {
            this("LEGACY_UNVERIFIED", SkillMatch.empty(), FactSet.empty(), List.of(), List.of(),
                    "", 0, "legacy analysis contract", signals, cases);
        }
    }

    public record SkillMatch(String id, List<String> ruleIds, double matchScore) {
        public static SkillMatch empty() { return new SkillMatch("", List.of(), 0); }
    }

    public record FactSet(String subject, String expected, String actual, String impact,
                          String timeRange, String scope) {
        public static FactSet empty() { return new FactSet("", "", "", "", "", ""); }
    }

    public record EvidenceCandidate(long messageId, String role, String quote, List<String> supports) { }

    public record SignalCandidate(String type, String severity, double confidence, String summary, String rationale) {}
    public record CaseCandidate(String title, String summary, String caseType, String severity,
                                double importance, double confidence, boolean criticalRisk,
                                boolean businessRelated, double businessRelevance, double evidenceScore,
                                boolean promoteToCase, boolean historicalHighRiskMatch, String reason,
                                boolean businessEvidence, String evidenceSource, String skillId) {
        public CaseCandidate(String title, String summary, String caseType, String severity,
                             double importance, double confidence, boolean criticalRisk,
                             boolean businessRelated, double businessRelevance, double evidenceScore,
                             boolean promoteToCase, boolean historicalHighRiskMatch, String reason,
                             boolean businessEvidence, String evidenceSource) {
            this(title, summary, caseType, severity, importance, confidence, criticalRisk,
                    businessRelated, businessRelevance, evidenceScore, promoteToCase,
                    historicalHighRiskMatch, reason, businessEvidence, evidenceSource, "");
        }
    }
}
