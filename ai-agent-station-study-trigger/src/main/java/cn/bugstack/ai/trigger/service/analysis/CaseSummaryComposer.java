package cn.bugstack.ai.trigger.service.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds Case text from verified facts. Model prose is kept only in the audit
 * reason and is never used as the business summary source.
 */
@Component
public class CaseSummaryComposer {

    public ComposedCase compose(AnalysisResultParser.AnalysisResult evaluation,
                                BoundSkillRule rule,
                                List<CaseEvidenceGate.EvidenceRef> evidence) {
        if (evaluation == null) {
            return new ComposedCase("待补充业务反馈", "当前未生成 Case：评测结果为空。", "无评测结果");
        }
        AnalysisResultParser.FactSet facts = evaluation.facts() == null
                ? AnalysisResultParser.FactSet.empty() : evaluation.facts();
        List<String> missing = new ArrayList<>(evaluation.missingInformation() == null
                ? List.of() : evaluation.missingInformation());
        appendMissing(missing, "业务对象", facts.subject());
        appendMissing(missing, "实际结果", facts.actual());
        appendMissing(missing, "业务影响", facts.impact());
        String ruleRef = rule == null ? "未绑定 Skill 规则" : rule.skillId() + " / " + rule.ruleId();
        String evidenceRef = evidence == null || evidence.isEmpty() ? "无" : "消息 " + evidence.stream()
                .map(item -> String.valueOf(item.messageId()))
                .distinct().collect(Collectors.joining("、"));
        String reason = "Skill 规则：" + ruleRef + "；证据：" + evidenceRef + "；评测理由：" + safe(evaluation.reason());
        if (!"CANDIDATE_CASE".equalsIgnoreCase(safe(evaluation.decision())) || !missing.isEmpty()) {
            String subject = safe(facts.subject()).isBlank() ? "业务反馈" : facts.subject();
            String summary = "当前未生成 Case。\n"
                    + "业务对象：" + display(facts.subject()) + "\n"
                    + "期望结果：" + display(facts.expected()) + "\n"
                    + "实际结果：" + display(facts.actual()) + "\n"
                    + "业务影响：" + display(facts.impact()) + "\n"
                    + "缺失信息：" + (missing.isEmpty() ? "待补充" : String.join("、", missing));
            return new ComposedCase("待补充：" + subject, summary, reason);
        }
        String title = safe(facts.subject()) + "：" + safe(facts.actual());
        String summary = "业务对象：" + display(facts.subject()) + "\n"
                + "期望：" + display(facts.expected()) + "\n"
                + "实际：" + display(facts.actual()) + "\n"
                + "业务影响：" + display(facts.impact()) + "\n"
                + "时间范围：" + display(facts.timeRange()) + "\n"
                + "影响范围：" + display(facts.scope()) + "\n"
                + "依据 Skill：" + ruleRef + "\n"
                + "证据：" + evidenceRef;
        return new ComposedCase(title, summary, reason);
    }

    private void appendMissing(List<String> missing, String label, String value) {
        if (safe(value).isBlank() && !missing.contains(label)) missing.add(label);
    }

    private String display(String value) { return safe(value).isBlank() ? "未提供" : safe(value); }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    public record BoundSkillRule(String skillId, String ruleId, String displayName) { }

    public record ComposedCase(String title, String summary, String extractionReason) { }
}
