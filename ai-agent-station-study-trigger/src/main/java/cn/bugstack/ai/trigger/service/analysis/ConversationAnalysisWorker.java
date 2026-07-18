package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.operations.CaseScoringService;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Component
public class ConversationAnalysisWorker {

    private static final String SYSTEM_PROMPT = """
            你是企业级智能体会话质量分析员。只返回一个纯 JSON 对象，不要 Markdown。
            固定结构：{"signals":[{"type":"USER_CORRECTION|REPEATED_QUESTION|IRRELEVANT_ANSWER|TOOL_FAILURE|OTHER","severity":"LOW|MEDIUM|HIGH|CRITICAL","confidence":0,"summary":"","rationale":""}],"cases":[{"title":"","summary":"","caseType":"BUG|FAQ|FEATURE|RUNBOOK|QUALITY","severity":"LOW|MEDIUM|HIGH|CRITICAL","importance":0,"confidence":0,"criticalRisk":false,"reason":""}]}。
            type、severity、caseType 必须使用上述英文枚举；title、summary、rationale、reason 必须使用简体中文。
            Case 只代表真实业务突发问题、产品缺陷、工具失败、流程风险或高价值可复用经验；不要把“1”“hi”等测试输入、普通问候、单次轻微误答生成 Case。
            signals 是模型观察信号，可以记录一次性质量现象；cases 必须有明确业务影响或可复用处理价值。
            只根据证据生成 signals/cases，证据不足返回空数组。所有分数范围为 0 到 100。
            """;

    @Resource private IAnalysisJobDao jobDao;
    @Resource private IChatMessageDao messageDao;
    @Resource private IAiSignalDao signalDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private ICaseEvidenceDao evidenceDao;
    @Resource private ICaseScoreSnapshotDao scoreSnapshotDao;
    @Resource private AnalysisResultParser parser;
    @Resource private ApplicationContext applicationContext;
    @Resource private ShortTermMemoryService shortTermMemoryService;
    @Resource(name = "mysqlJdbcTemplate") private JdbcTemplate jdbcTemplate;
    private final CaseScoringService scoringService = new CaseScoringService();
    private final ConversationQualificationPolicy qualificationPolicy = new ConversationQualificationPolicy();

    @Value("${agent.analysis.enabled:true}") private boolean enabled;

    @Scheduled(fixedDelayString = "${agent.analysis.poll-delay-ms:5000}")
    public void processNext() {
        if (!enabled) return;
        AnalysisJob job = jobDao.queryClaimable();
        if (job == null || jobDao.claim(job.getId(), LocalDateTime.now().plusMinutes(2)) != 1) return;
        try {
            try { shortTermMemoryService.refreshIfNeeded(job.getAgentId(), job.getSessionId(), job.getModelId()); }
            catch (Exception memoryError) { log.warn("Short-term memory refresh failed for session {}", job.getSessionId(), memoryError); }
            List<ChatMessage> messages = messageDao.queryBySessionId(job.getSessionId());
            int explicitNegativeFeedback = explicitNegativeFeedback(job);
            boolean admitted = qualificationPolicy.shouldAnalyze(messages.stream()
                    .map(message -> new ConversationQualificationPolicy.ConversationMessage(message.getRole(), message.getContent()))
                    .toList(), explicitNegativeFeedback);
            if (!admitted) {
                log.debug("Conversation analysis skipped by admission policy, session={}, assistantMessage={}",
                        job.getSessionId(), job.getAssistantMessageId());
                jobDao.markComplete(job.getId());
                return;
            }
            AnalysisResultParser.AnalysisResult result = parser.parse(analyze(job, messages));
            persist(job, result, explicitNegativeFeedback);
            jobDao.markComplete(job.getId());
        } catch (Exception exception) {
            try { shortTermMemoryService.refreshIfNeeded(job.getAgentId(), job.getSessionId(), job.getModelId()); }
            catch (Exception memoryError) { log.warn("Short-term memory refresh failed for session {}", job.getSessionId(), memoryError); }
            int nextAttempt = job.getAttempts() == null ? 1 : job.getAttempts() + 1;
            String state = nextAttempt >= job.getMaxAttempts() ? "FAILED" : "RETRY";
            String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            jobDao.markFailure(job.getId(), state, error.substring(0, Math.min(2000, error.length())));
            log.warn("Conversation analysis failed, jobId={}, state={}", job.getId(), state, exception);
        }
    }

    private String analyze(AnalysisJob job, List<ChatMessage> messages) {
        OpenAiChatModel model = applicationContext.getBean(
                AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(job.getModelId()), OpenAiChatModel.class);
        StringBuilder evidence = new StringBuilder("agentId=").append(job.getAgentId()).append('\n');
        int start = Math.max(0, messages.size() - 30);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            evidence.append('[').append(message.getId()).append(' ').append(message.getRole()).append("] ")
                    .append(message.getContent() == null ? "" : message.getContent()).append('\n');
        }
        return ChatClient.builder(model).defaultSystem(SYSTEM_PROMPT).build()
                .prompt().user(evidence.toString()).call().content();
    }

    private void persist(AnalysisJob job, AnalysisResultParser.AnalysisResult result, int explicitNegativeFeedback) {
        LocalDateTime now = LocalDateTime.now();
        for (AnalysisResultParser.SignalCandidate candidate : result.signals()) {
            signalDao.insert(AiSignal.builder().agentId(job.getAgentId()).sessionId(job.getSessionId())
                    .assistantMessageId(job.getAssistantMessageId()).signalType(candidate.type())
                    .sourceType("AI_INFERRED").severity(candidate.severity()).confidence(candidate.confidence())
                    .summary(candidate.summary()).rationale(candidate.rationale()).modelId(job.getModelId())
                    .status("OBSERVED").createdAt(now).build());
        }
        for (AnalysisResultParser.CaseCandidate candidate : result.cases()) {
            upsertCase(job, candidate, now, explicitNegativeFeedback);
        }
    }

    private void upsertCase(AnalysisJob job, AnalysisResultParser.CaseCandidate candidate, LocalDateTime now,
                            int explicitNegativeFeedback) {
        String caseId = caseId(job.getAgentId(), candidate.title());
        evidenceDao.insertIgnore(CaseEvidence.builder().caseId(caseId).agentId(job.getAgentId())
                .evidenceType("MESSAGE").evidenceId(job.getAssistantMessageId()).sessionId(job.getSessionId())
                .messageId(job.getAssistantMessageId()).excerpt(candidate.reason()).createdAt(now).build());
        int distinctSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT session_id) FROM case_evidence WHERE case_id = ?", Integer.class, caseId);
        if (!qualificationPolicy.shouldPromoteCase(distinctSessions, explicitNegativeFeedback,
                candidate.confidence(), candidate.criticalRisk())) {
            return;
        }
        AiCase existing = caseDao.queryByAgentAndCaseId(job.getAgentId(), caseId);
        int frequency = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM case_evidence WHERE case_id = ?", Integer.class, caseId);
        int affected = distinctSessions;
        double severity = switch (candidate.severity()) { case "CRITICAL" -> 100; case "HIGH" -> 75; case "MEDIUM" -> 50; default -> 25; };
        double negative = Math.min(100, explicitNegativeFeedback * 50d);
        CaseScoringService.CaseScoreBreakdown score = scoringService.score(new CaseScoringService.CaseScoreInput(
                severity, negative, Math.min(100, frequency * 10d), candidate.importance(), 100, 0,
                candidate.confidence(), candidate.criticalRisk(), false));
        String status = existing == null ? "CANDIDATE" : existing.getStatus();
        AiCase record = AiCase.builder().caseId(caseId).agentId(job.getAgentId()).title(candidate.title())
                .summary(candidate.summary()).caseType(candidate.caseType()).severity(candidate.severity())
                .frequency(frequency).affectedSessions(affected).importanceScore(candidate.importance())
                .confidence(candidate.confidence()).totalScore(score.total()).status(status).skillId("")
                .sourceModel(job.getModelId()).extractionReason(candidate.reason()).owner("").resolution("")
                .lastSeenAt(now).createdAt(now).updatedAt(now).build();
        if (existing == null) caseDao.insert(record); else caseDao.updateAnalysis(record);
        scoreSnapshotDao.insert(CaseScoreSnapshot.builder().caseId(caseId).agentId(job.getAgentId())
                .totalScore(score.total()).severityScore(score.severityContribution())
                .negativeFeedbackScore(score.negativeFeedbackContribution()).frequencyScore(score.frequencyContribution())
                .importanceScore(score.importanceContribution()).recencyScore(score.recencyContribution())
                .unresolvedAgeScore(score.unresolvedAgeContribution()).confidenceScore(score.confidenceContribution())
                .priorityFloorApplied(score.priorityFloorApplied() ? 1 : 0).rationale(candidate.reason()).createdAt(now).build());
    }

    private int explicitNegativeFeedback(AnalysisJob job) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_feedback
                WHERE agent_id = ? AND assistant_message_id = ?
                  AND (feedback_type IN ('THUMBS_DOWN', 'NEGATIVE') OR rating IS NOT NULL AND rating <= 2)
                """, Integer.class, job.getAgentId(), job.getAssistantMessageId());
        return value == null ? 0 : value;
    }

    private String caseId(String agentId, String title) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((agentId + ":" + title.trim().toLowerCase()).getBytes(StandardCharsets.UTF_8));
            return "case-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
