package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Server-side admission gate for business Cases. It never trusts a model's
 * promote flag or score as the final decision.
 */
@Component
public class CaseEvidenceGate {

    private static final int MIN_SERVER_SCORE = 75;

    public GateDecision evaluate(String agentId,
                                 List<ChatMessage> messages,
                                 AnalysisResultParser.AnalysisResult evaluation,
                                 BoundSkillContext skillContext,
                                 ExistingCaseContext existingCase) {
        if (evaluation == null) return needMore("评测结果为空", List.of());
        String decision = safe(evaluation.decision()).toUpperCase(Locale.ROOT);
        BoundSkillContext bound = skillContext == null ? BoundSkillContext.empty() : skillContext;
        if (agentId == null || agentId.isBlank() || !agentId.equals(bound.agentId()) || bound.skillId().isBlank()) {
            if ("NOT_ELIGIBLE".equals(decision)) {
                return new GateDecision("NOT_ELIGIBLE", List.of(), List.of(), 0, evaluation.reason(), "");
            }
            return new GateDecision("NOT_ELIGIBLE", List.of(), List.of("当前 Agent 未绑定有效业务 Skill"), 0,
                    "当前 Agent 未绑定有效业务 Skill，不能生成业务 Feedback/Case", "");
        }
        if ("NOT_ELIGIBLE".equals(decision)) {
            return new GateDecision("NOT_ELIGIBLE", List.of(), List.of(), 0, evaluation.reason(), "");
        }
        if ("LEGACY_UNVERIFIED".equals(decision)) {
            return needMore("旧版评测契约缺少结构化证据", List.of("重新执行结构化评测"));
        }
        if ("FEEDBACK_ONLY".equals(decision)) {
            return new GateDecision("FEEDBACK_CAPTURED", List.of(), evaluation.missingInformation(),
                    serverScore(evaluation, false, 0), evaluation.reason(), "");
        }
        if (!"NEED_MORE_INFO".equals(decision) && !"CANDIDATE_CASE".equals(decision)) {
            return needMore("未知评测状态，已阻止 Case 晋升", List.of("重新评测"));
        }

        // bound context is initialized before the decision-specific branches.
        if (agentId == null || agentId.isBlank() || !agentId.equals(bound.agentId())) {
            return needMore("当前 Agent 上下文不匹配", List.of("确认 Agent 与 Skill 绑定关系"));
        }
        String skillId = safe(evaluation.skill().id());
        if (skillId.isBlank() || !skillId.equals(bound.skillId())) {
            return needMore("评测 Skill 未绑定到当前 Agent", List.of("绑定并读取业务 Skill"));
        }
        if (evaluation.skill().ruleIds().isEmpty()
                || !bound.ruleIds().containsAll(evaluation.skill().ruleIds())) {
            return needMore("评测引用了当前 Agent 不存在的 Skill 规则", List.of("补充有效 ruleId"));
        }
        if (bound.mcpIds().isEmpty()) {
            return needMore("当前 Agent 未绑定有效业务 MCP，禁止将 Feedback 升级为 Case",
                    List.of("绑定业务 MCP 并获取实际工具回执"));
        }
        List<String> missing = new ArrayList<>(evaluation.missingInformation());
        appendMissing(missing, "业务对象", evaluation.facts().subject());
        appendMissing(missing, "实际结果", evaluation.facts().actual());
        appendMissing(missing, "业务影响", evaluation.facts().impact());

        Map<Long, ChatMessage> messageIndex = index(messages);
        List<EvidenceRef> accepted = new ArrayList<>();
        List<String> evidenceProblems = new ArrayList<>();
        for (AnalysisResultParser.EvidenceCandidate candidate : evaluation.evidence()) {
            ChatMessage message = messageIndex.get(candidate.messageId());
            if (message == null) {
                evidenceProblems.add("消息 " + candidate.messageId() + " 不属于当前会话");
                continue;
            }
            String role = safe(candidate.role()).toLowerCase(Locale.ROOT);
            if ("assistant".equals(role)) {
                evidenceProblems.add("助手消息不能作为业务事实");
                continue;
            }
            if (!Set.of("user", "operator", "tool").contains(role)) {
                evidenceProblems.add("不允许的证据角色：" + role);
                continue;
            }
            if ("tool".equals(role) && safe(message.getToolName()).isBlank()) {
                evidenceProblems.add("工具证据缺少真实 toolName，不能证明来自绑定 MCP");
                continue;
            }
            String quote = normalize(candidate.quote());
            if (quote.isBlank() || !normalize(message.getContent()).contains(quote)) {
                evidenceProblems.add("消息 " + candidate.messageId() + " 的证据摘录无法与原文匹配");
                continue;
            }
            accepted.add(new EvidenceRef(candidate.messageId(), role, quote, candidate.supports()));
        }
        if (accepted.isEmpty()) evidenceProblems.add("没有用户、运维或业务工具的有效证据");
        if (!evidenceProblems.isEmpty()) missing.addAll(evidenceProblems);
        missing = missing.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();

        boolean completeFacts = missing.stream().noneMatch(item -> Set.of("业务对象", "实际结果", "业务影响").contains(item));
        boolean enoughEvidence = accepted.size() >= 2 || (accepted.size() == 1 && highImpact(evaluation.severity()));
        boolean candidateRequested = "CANDIDATE_CASE".equals(decision);
        boolean hasBusinessToolEvidence = accepted.stream().anyMatch(item -> "tool".equals(item.role()));
        int score = serverScore(evaluation, completeFacts, accepted.size());
        if (!candidateRequested || !completeFacts || !enoughEvidence || !hasBusinessToolEvidence
                || !missing.isEmpty() || score < MIN_SERVER_SCORE) {
            if (!hasBusinessToolEvidence && missing.isEmpty()) {
                missing.add("至少一条来自绑定 MCP 的工具结果");
            }
            return new GateDecision("NEED_MORE_INFO", List.copyOf(accepted), missing, score,
                    firstReason(evaluation.reason(), "证据门禁未通过，暂不生成 Case"), fingerprint(accepted));
        }

        String fingerprint = fingerprint(accepted);
        ExistingCaseContext existing = existingCase == null ? ExistingCaseContext.empty() : existingCase;
        if (existing.caseAlreadyExists() && fingerprint.equals(existing.evidenceFingerprint())) {
            return new GateDecision("DUPLICATE", List.copyOf(accepted), List.of(), score,
                    "证据指纹与现有 Case 相同，跳过重复摘要", fingerprint);
        }
        return new GateDecision("CANDIDATE_CASE", List.copyOf(accepted), List.of(), score,
                firstReason(evaluation.reason(), "通过 Skill 规则和证据门禁"), fingerprint);
    }

    private Map<Long, ChatMessage> index(List<ChatMessage> messages) {
        if (messages == null) return Map.of();
        Map<Long, ChatMessage> result = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            if (message != null && message.getId() != null) result.put(message.getId(), message);
        }
        return result;
    }

    private int serverScore(AnalysisResultParser.AnalysisResult evaluation, boolean completeFacts, int evidenceCount) {
        int score = evaluation.skill() != null && !safe(evaluation.skill().id()).isBlank() ? 20 : 0;
        score += evaluation.skill() != null && !evaluation.skill().ruleIds().isEmpty() ? 20 : 0;
        score += completeFacts ? 30 : 0;
        score += Math.min(30, evidenceCount * 15);
        return Math.min(100, score);
    }

    private boolean highImpact(String severity) {
        return Set.of("P0", "P1", "CRITICAL", "HIGH").contains(safe(severity).toUpperCase(Locale.ROOT));
    }

    private void appendMissing(List<String> missing, String label, String value) {
        if (safe(value).isBlank() && !missing.contains(label)) missing.add(label);
    }

    private GateDecision needMore(String reason, List<String> missing) {
        return new GateDecision("NEED_MORE_INFO", List.of(), List.copyOf(missing), 0, reason, "");
    }

    private String firstReason(String reason, String fallback) {
        return safe(reason).isBlank() ? fallback : reason;
    }

    private String fingerprint(List<EvidenceRef> evidence) {
        String payload = evidence.stream().sorted(Comparator.comparingLong(EvidenceRef::messageId))
                .map(item -> item.messageId() + "|" + item.role() + "|" + item.quote())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        if (payload.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 Case 证据指纹", exception);
        }
    }

    private String normalize(String value) { return safe(value).replaceAll("\\s+", " ").trim(); }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    public record BoundSkillContext(String agentId, String skillId, Set<String> ruleIds, Set<String> mcpIds) {
        public BoundSkillContext {
            ruleIds = ruleIds == null ? Set.of() : Set.copyOf(ruleIds);
            mcpIds = mcpIds == null ? Set.of() : Set.copyOf(mcpIds);
        }
        public BoundSkillContext(String agentId, String skillId, Set<String> ruleIds) {
            this(agentId, skillId, ruleIds, Set.of());
        }
        public static BoundSkillContext empty() { return new BoundSkillContext("", "", Set.of(), Set.of()); }
    }

    public record ExistingCaseContext(String evidenceFingerprint, boolean caseAlreadyExists) {
        public static ExistingCaseContext empty() { return new ExistingCaseContext("", false); }
    }

    public record EvidenceRef(long messageId, String role, String quote, List<String> supports) {
        public EvidenceRef {
            supports = supports == null ? List.of() : List.copyOf(supports);
        }
    }

    public record GateDecision(String state, List<EvidenceRef> acceptedEvidence, List<String> missingInformation,
                               int serverScore, String reason, String evidenceFingerprint) {
        public GateDecision {
            acceptedEvidence = acceptedEvidence == null ? List.of() : List.copyOf(acceptedEvidence);
            missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        }
    }
}
