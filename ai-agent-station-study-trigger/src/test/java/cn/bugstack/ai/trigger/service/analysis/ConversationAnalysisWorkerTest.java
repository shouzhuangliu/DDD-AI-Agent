package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.AnalysisJob;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationAnalysisWorkerTest {

    private ConversationAnalysisWorker worker;
    private ICaseEvaluationSnapshotDao snapshotDao;
    private IAiCaseDao caseDao;
    private IAiSignalDao signalDao;
    private CaseEvidenceGate evidenceGate;
    private AnalysisResultParser parser = new AnalysisResultParser();

    @BeforeEach
    void setUp() {
        worker = new ConversationAnalysisWorker();
        snapshotDao = mock(ICaseEvaluationSnapshotDao.class);
        caseDao = mock(IAiCaseDao.class);
        signalDao = mock(IAiSignalDao.class);
        evidenceGate = mock(CaseEvidenceGate.class);
        ReflectionTestUtils.setField(worker, "evaluationSnapshotDao", snapshotDao);
        ReflectionTestUtils.setField(worker, "caseDao", caseDao);
        ReflectionTestUtils.setField(worker, "evidenceGate", evidenceGate);
        ReflectionTestUtils.setField(worker, "evaluationContextBuilder", mock(AgentEvaluationContextBuilder.class));
        ReflectionTestUtils.setField(worker, "signalDao", signalDao);
        ReflectionTestUtils.setField(worker, "summaryComposer", mock(CaseSummaryComposer.class));
        ReflectionTestUtils.setField(worker, "messageDao", mock(IChatMessageDao.class));
        ReflectionTestUtils.setField(worker, "evidenceDao", mock(ICaseEvidenceDao.class));
        ReflectionTestUtils.setField(worker, "scoreSnapshotDao", mock(ICaseScoreSnapshotDao.class));
        ReflectionTestUtils.setField(worker, "jdbcTemplate", mock(JdbcTemplate.class));
        when(caseDao.queryBySession("inventory", "session-1", 1)).thenReturn(List.of());
    }

    @Test
    void notEligiblePersistsAuditSnapshotButNeverCreatesCase() {
        var result = parser.parse("""
                {"decision":"NOT_ELIGIBLE","skill":{"id":"","ruleIds":[],"matchScore":0},"facts":{},
                 "evidence":[],"missingInformation":[],"severity":"P3","confidence":99,"reason":"普通问候"}
                """);
        when(evidenceGate.evaluate(any(), any(), eq(result), any(), any()))
                .thenReturn(new CaseEvidenceGate.GateDecision("NOT_ELIGIBLE", List.of(), List.of(), 0, "普通问候", ""));

        worker.persist(job(), List.of(message(1, "user", "你好")), result, 0, null,
                new CaseAnalysisCadencePolicy.Decision(true, "test", "fp", 2));

        verify(snapshotDao).insertIgnore(any());
        verify(caseDao, never()).insert(any());
        verify(caseDao, never()).updateAnalysis(any());
    }

    @Test
    void notEligibleConversationDoesNotPersistBusinessSignals() {
        var result = parser.parse("""
                {"decision":"NOT_ELIGIBLE","skill":{"id":"","ruleIds":[],"matchScore":0},
                 "facts":{},"evidence":[],"missingInformation":[],"severity":"P3","confidence":10,
                 "reason":"普通查询，不是业务反馈","signals":[{"type":"USER_CORRECTION","severity":"LOW",
                 "confidence":80,"summary":"用户纠正了助手","rationale":"但会话未命中业务 Skill"}]}
                """);
        when(evidenceGate.evaluate(any(), any(), eq(result), any(), any()))
                .thenReturn(new CaseEvidenceGate.GateDecision("NOT_ELIGIBLE", List.of(), List.of(), 0,
                        "普通查询，不是业务反馈", ""));

        worker.persist(job(), List.of(message(1, "user", "查询今日反馈")), result, 0, null,
                new CaseAnalysisCadencePolicy.Decision(true, "test", "fp", 2));

        verify(signalDao, never()).insert(any());
    }

    @Test
    void needMoreInfoPersistsAuditSnapshotButNeverCreatesCase() {
        var result = parser.parse("""
                {"decision":"NEED_MORE_INFO","skill":{"id":"inventory-feedback-agent","ruleIds":["INV-FACT-COMPLETENESS"],"matchScore":80},
                 "facts":{"subject":"DDR5","expected":"可售","actual":"缺货","impact":""},
                 "evidence":[{"messageId":1,"role":"user","quote":"DDR5 缺货","supports":["subject","actual"]}],
                 "missingInformation":["业务影响"],"severity":"P2","confidence":60,"reason":"缺少影响"}
                """);
        when(evidenceGate.evaluate(any(), any(), eq(result), any(), any()))
                .thenReturn(new CaseEvidenceGate.GateDecision("NEED_MORE_INFO", List.of(), List.of("业务影响"), 50, "缺少影响", "fp"));

        worker.persist(job(), List.of(message(1, "user", "DDR5 缺货")), result, 0, null,
                new CaseAnalysisCadencePolicy.Decision(true, "test", "fp", 2));

        verify(snapshotDao).insertIgnore(any());
        verify(caseDao, never()).insert(any());
    }

    private AnalysisJob job() {
        return AnalysisJob.builder().id(1L).agentId("inventory").sessionId("session-1")
                .assistantMessageId(2L).modelId("deepseek-v4-flash").policyVersion(AnalysisJobQueue.POLICY_VERSION)
                .attempts(0).maxAttempts(3).build();
    }

    private ChatMessage message(long id, String role, String content) {
        return ChatMessage.builder().id(id).sessionId("session-1").agentId("inventory")
                .role(role).content(content).build();
    }
}
