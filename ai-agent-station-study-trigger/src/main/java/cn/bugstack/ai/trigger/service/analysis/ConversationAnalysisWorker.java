package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.operations.CaseScoringService;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Conversation quality worker.
 *
 * <p>The model only proposes a structured evaluation. Cadence, Skill binding,
 * evidence ownership, scoring and Case promotion are all decided server-side.</p>
 */
@Slf4j
@Component
public class ConversationAnalysisWorker {

    private static final String SYSTEM_PROMPT = """
            你是企业级 Agent 业务质量评测器，只返回一个纯 JSON 对象，不要 Markdown。
            你的职责是根据当前 Agent 已绑定的业务 Skill，评估本轮对话是否包含可验证的业务反馈；不要把普通闲聊、单字测试、助手自说自话、工具报错当成 Case。
            你只能引用当前会话中真实存在的消息作为 evidence，quote 必须是原文连续片段，不能编造消息 ID、商品、订单或影响。

            输出契约：
            {
              "decision":"NOT_ELIGIBLE|FEEDBACK_ONLY|NEED_MORE_INFO|CANDIDATE_CASE",
              "skill":{"id":"当前绑定Skill ID，没有则空字符串","ruleIds":["规则ID"],"matchScore":0},
              "facts":{"subject":"业务对象，如 SKU/商品/库存","expected":"期望结果","actual":"实际结果","impact":"业务影响","timeRange":"时间范围","scope":"影响范围"},
              "evidence":[{"messageId":123,"role":"user|operator|tool","quote":"原文连续片段","supports":["规则ID","事实字段"]}],
              "missingInformation":["仍缺少的信息"],
              "severity":"P0|P1|P2|P3",
              "confidence":0,
              "reason":"中文评测理由",
              "signals":[{"type":"USER_CORRECTION|REPEATED_QUESTION|IRRELEVANT_ANSWER|OTHER","severity":"LOW|MEDIUM|HIGH|CRITICAL","confidence":0,"summary":"中文观察","rationale":"中文依据"}]
            }

            决策规则：
            1. NOT_ELIGIBLE：与绑定业务 Skill 无关，或只是问候、1、OK、继续、占位回复。
            2. FEEDBACK_ONLY：确认记录了业务反馈，但对象、实际结果或影响仍不完整；只能进入 Feedback 评测队列，不能生成 Case。
            3. NEED_MORE_INFO：可能相关但事实或证据不足；必须填写 missingInformation。
            4. CANDIDATE_CASE：只有在明确引用当前绑定 Skill 的 ruleId，并且事实、影响和至少一条真实证据完整时才可提出。严重程度 P0/P1 只能有业务证据支持。
            5. TOOL_FAILURE、MCP_FAILURE、MODEL_FAILURE、MODEL_RATE_LIMIT、EXECUTION_FAILURE 属于运行时观测，不能放进 signals；它们由工具日志单独记录。NOT_ELIGIBLE 时 signals 必须为空。
            6. 模型分数只是参考，服务端会重新计算证据门禁；不要输出 promoteToCase 字段。
            """;

    private static final String BUSINESS_BOUNDARY_PROMPT = """
            当前评测边界：只允许使用当前 Agent 绑定的 Skill 文档和其引用的业务工具结果。MCP 连接失败、超时、参数错误、模型限流和内部异常只能形成运行观察 Signal，不能成为业务事实或 Case 证据。没有有效绑定 Skill 时，decision 必须是 NOT_ELIGIBLE 或 NEED_MORE_INFO。
            """;

    @Resource private IAnalysisJobDao jobDao;
    @Resource private IChatMessageDao messageDao;
    @Resource private IAiSignalDao signalDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private ICaseEvidenceDao evidenceDao;
    @Resource private ICaseScoreSnapshotDao scoreSnapshotDao;
    @Resource private ICaseEvaluationSnapshotDao evaluationSnapshotDao;
    @Resource private AnalysisResultParser parser;
    @Resource private AgentEvaluationContextBuilder evaluationContextBuilder;
    @Resource private ApplicationContext applicationContext;
    @Resource private ShortTermMemoryService shortTermMemoryService;
    @Resource private CaseEvidenceGate evidenceGate;
    @Resource private CaseSummaryComposer summaryComposer;
    @Resource(name = "mysqlJdbcTemplate") private JdbcTemplate jdbcTemplate;

    private final CaseScoringService scoringService = new CaseScoringService();
    private final CaseAnalysisCadencePolicy cadencePolicy = new CaseAnalysisCadencePolicy();

    @Value("${agent.analysis.enabled:true}") private boolean enabled;

    @Scheduled(fixedDelayString = "${agent.analysis.poll-delay-ms:5000}")
    public void processNext() {
        if (!enabled) return;
        AnalysisJob job = jobDao.queryClaimable();
        if (job == null || jobDao.claim(job.getId(), LocalDateTime.now().plusMinutes(2)) != 1) return;
        try {
            if (!AnalysisJobQueue.POLICY_VERSION.equals(job.getPolicyVersion())) {
                log.info("跳过旧版对话评测任务，jobId={}, policyVersion={}", job.getId(), job.getPolicyVersion());
                jobDao.markComplete(job.getId());
                return;
            }
            try {
                shortTermMemoryService.refreshIfNeeded(job.getAgentId(), job.getSessionId(), job.getModelId());
            } catch (Exception memoryError) {
                log.warn("短期记忆刷新失败，继续执行 Case 评测，session={}", job.getSessionId(), memoryError);
            }

            List<ChatMessage> messages = messageDao.queryBySessionId(job.getSessionId());
            int explicitNegativeFeedback = explicitNegativeFeedback(job);
            CaseEvaluationSnapshot latest = evaluationSnapshotDao.queryLatest(job.getAgentId(), job.getSessionId());
            CaseAnalysisCadencePolicy.Decision cadence = cadencePolicy.shouldEvaluate(
                    messages.stream().map(message -> new CaseAnalysisCadencePolicy.ConversationMessage(
                            message.getId() == null ? 0 : message.getId(), message.getRole(), message.getContent())).toList(),
                    latest == null ? new CaseAnalysisCadencePolicy.EvaluationCursor(0, 0, "")
                            : new CaseAnalysisCadencePolicy.EvaluationCursor(
                            latest.getAssistantMessageId() == null ? 0 : latest.getAssistantMessageId(), 0,
                            latest.getEvidenceFingerprint()),
                    explicitNegativeFeedback > 0, false);
            if (!cadence.required()) {
                log.debug("按评测频率门禁跳过，session={}, reason={}", job.getSessionId(), cadence.reason());
                jobDao.markComplete(job.getId());
                return;
            }

            AnalysisResultParser.AnalysisResult result = parser.parse(analyze(job, messages));
            persist(job, messages, result, explicitNegativeFeedback, latest, cadence);
            jobDao.markComplete(job.getId());
        } catch (Exception exception) {
            int nextAttempt = job.getAttempts() == null ? 1 : job.getAttempts() + 1;
            String state = nextAttempt >= job.getMaxAttempts() ? "FAILED" : "RETRY";
            String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            String safeError = error.substring(0, Math.min(2000, error.length()));
            if (ReActExecuteStrategy.isRateLimitError(error)) {
                jobDao.deferFailure(job.getId(), state, safeError, LocalDateTime.now().plusMinutes(2));
            } else {
                jobDao.markFailure(job.getId(), state, safeError);
            }
            log.warn("对话业务评测失败，jobId={}, state={}", job.getId(), state, exception);
        }
    }

    private String analyze(AnalysisJob job, List<ChatMessage> messages) {
        OpenAiChatModel model = applicationContext.getBean(
                AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(job.getModelId()), OpenAiChatModel.class);
        return ChatClient.builder(model).defaultSystem(SYSTEM_PROMPT + "\n" + BUSINESS_BOUNDARY_PROMPT).build()
                .prompt().user(evaluationContextBuilder.build(job.getAgentId(), messages)).call().content();
    }

    void persist(AnalysisJob job, List<ChatMessage> messages,
                         AnalysisResultParser.AnalysisResult result, int explicitNegativeFeedback,
                         CaseEvaluationSnapshot latest, CaseAnalysisCadencePolicy.Decision cadence) {
        LocalDateTime now = LocalDateTime.now();
        CaseEvidenceGate.BoundSkillContext boundSkill = evaluationContextBuilder.boundSkillContext(job.getAgentId());
        List<AiCase> sessionCases = caseDao.queryBySession(job.getAgentId(), job.getSessionId(), 1);
        CaseEvidenceGate.ExistingCaseContext existingCase = new CaseEvidenceGate.ExistingCaseContext(
                latest == null ? "" : blank(latest.getEvidenceFingerprint()), sessionCases != null && !sessionCases.isEmpty());
        CaseEvidenceGate.GateDecision gate = evidenceGate.evaluate(
                job.getAgentId(), messages, result, boundSkill, existingCase);

        // Runtime observations remain available to the operational trace, but a
        // non-business conversation must not become an AI business signal.
        for (AnalysisResultParser.SignalCandidate candidate : result.signals()) {
            boolean runtimeObservation = AnalysisResultParser.isRuntimeOnlySignalType(candidate.type());
            if (!runtimeObservation && "NOT_ELIGIBLE".equals(gate.state())) continue;
            signalDao.insert(AiSignal.builder().agentId(job.getAgentId()).sessionId(job.getSessionId())
                    .assistantMessageId(job.getAssistantMessageId()).signalType(candidate.type())
                    .sourceType(runtimeObservation ? "RUNTIME_OBSERVATION" : "AI_INFERRED")
                    .severity(candidate.severity()).confidence(candidate.confidence())
                    .summary(candidate.summary()).rationale(candidate.rationale()).modelId(job.getModelId())
                    .status("OBSERVED").createdAt(now).build());
        }

        String fingerprint = gate.evidenceFingerprint().isBlank() ? cadence.evidenceFingerprint() : gate.evidenceFingerprint();
        evaluationSnapshotDao.insertIgnore(CaseEvaluationSnapshot.builder()
                .idempotencyKey("case-evaluation:" + job.getAgentId() + ":" + job.getSessionId() + ":" + job.getAssistantMessageId())
                .agentId(job.getAgentId()).sessionId(job.getSessionId()).assistantMessageId(job.getAssistantMessageId())
                .policyVersion(AnalysisJobQueue.POLICY_VERSION).decision(gate.state())
                .skillId(result.skill().id()).ruleIdsJson(JSON.toJSONString(result.skill().ruleIds()))
                .factsJson(JSON.toJSONString(result.facts())).missingInformationJson(JSON.toJSONString(gate.missingInformation()))
                .evidenceJson(JSON.toJSONString(gate.acceptedEvidence())).confidence(result.confidence())
                .serverScore(gate.serverScore()).reason(gate.reason()).evidenceFingerprint(fingerprint).createdAt(now).build());

        if (!"CANDIDATE_CASE".equals(gate.state())) {
            log.info("Case 评测未生成 Case，agent={}, session={}, state={}, score={}, reason={}",
                    job.getAgentId(), job.getSessionId(), gate.state(), gate.serverScore(), gate.reason());
            return;
        }

        CaseSummaryComposer.BoundSkillRule rule = new CaseSummaryComposer.BoundSkillRule(
                result.skill().id(), result.skill().ruleIds().get(0), result.skill().id());
        CaseSummaryComposer.ComposedCase composed = summaryComposer.compose(result, rule, gate.acceptedEvidence());
        upsertVerifiedCase(job, result, gate, composed, explicitNegativeFeedback, now);
    }

    private void upsertVerifiedCase(AnalysisJob job, AnalysisResultParser.AnalysisResult result,
                                    CaseEvidenceGate.GateDecision gate,
                                    CaseSummaryComposer.ComposedCase composed,
                                    int explicitNegativeFeedback, LocalDateTime now) {
        String caseId = caseId(job.getAgentId(), composed.title());
        for (CaseEvidenceGate.EvidenceRef reference : gate.acceptedEvidence()) {
            ChatMessage message = messageDao.queryById(reference.messageId());
            evidenceDao.insertIgnore(CaseEvidence.builder().caseId(caseId).agentId(job.getAgentId())
                    .evidenceType("MESSAGE").evidenceId(reference.messageId()).sessionId(job.getSessionId())
                    .messageId(reference.messageId()).excerpt(reference.quote()).evidenceRole(reference.role())
                    .skillRuleId(result.skill().ruleIds().stream().findFirst().orElse(""))
                    .supportsJson(JSON.toJSONString(reference.supports())).createdAt(now).build());
            if (message == null) log.warn("Case 证据消息不存在，messageId={}", reference.messageId());
        }
        Integer frequencyValue = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM case_evidence WHERE case_id = ? AND agent_id = ?", Integer.class, caseId, job.getAgentId());
        Integer distinctSessionsValue = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT session_id) FROM case_evidence WHERE case_id = ? AND agent_id = ?", Integer.class, caseId, job.getAgentId());
        int frequency = frequencyValue == null ? 0 : frequencyValue;
        int affectedSessions = distinctSessionsValue == null ? 0 : distinctSessionsValue;
        double severity = severityScore(result.severity());
        double negative = Math.min(100, explicitNegativeFeedback * 50d);
        CaseScoringService.CaseScoreBreakdown score = scoringService.score(new CaseScoringService.CaseScoreInput(
                severity, negative, Math.min(100, frequency * 10d), result.confidence(), 100, 0,
                result.confidence(), "P0".equalsIgnoreCase(result.severity()), false));
        AiCase existing = caseDao.queryByAgentAndCaseId(job.getAgentId(), caseId);
        String status = existing == null ? "CANDIDATE" : blank(existing.getStatus());
        AiCase record = AiCase.builder().caseId(caseId).agentId(job.getAgentId()).title(composed.title())
                .summary(composed.summary()).caseType("BUSINESS_ISSUE").severity(mapSeverity(result.severity()))
                .frequency(frequency).affectedSessions(affectedSessions).importanceScore(result.confidence())
                .confidence(result.confidence()).totalScore(score.total()).status(status).skillId(result.skill().id())
                .sourceModel(job.getModelId()).extractionReason(composed.extractionReason()).owner("").resolution("")
                .lastSeenAt(now).createdAt(now).updatedAt(now).build();
        if (existing == null) caseDao.insert(record); else caseDao.updateAnalysis(record);
        scoreSnapshotDao.insert(CaseScoreSnapshot.builder().caseId(caseId).agentId(job.getAgentId())
                .totalScore(score.total()).severityScore(score.severityContribution())
                .negativeFeedbackScore(score.negativeFeedbackContribution()).frequencyScore(score.frequencyContribution())
                .importanceScore(score.importanceContribution()).recencyScore(score.recencyContribution())
                .unresolvedAgeScore(score.unresolvedAgeContribution()).confidenceScore(score.confidenceContribution())
                .priorityFloorApplied(score.priorityFloorApplied() ? 1 : 0)
                .rationale(gate.reason()).createdAt(now).build());
    }

    private int explicitNegativeFeedback(AnalysisJob job) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_feedback
                WHERE agent_id = ? AND assistant_message_id = ?
                  AND (feedback_type IN ('THUMBS_DOWN', 'NEGATIVE') OR rating IS NOT NULL AND rating <= 2)
                """, Integer.class, job.getAgentId(), job.getAssistantMessageId());
        return value == null ? 0 : value;
    }

    private double severityScore(String severity) {
        return switch (blank(severity).toUpperCase(Locale.ROOT)) {
            case "P0" -> 100;
            case "P1" -> 85;
            case "P2" -> 55;
            default -> 25;
        };
    }

    private String mapSeverity(String severity) {
        return switch (blank(severity).toUpperCase(Locale.ROOT)) {
            case "P0" -> "CRITICAL";
            case "P1" -> "HIGH";
            case "P2" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private String caseId(String agentId, String title) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((agentId + ":" + blank(title).toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return "case-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String blank(String value) { return value == null ? "" : value.trim(); }
}
