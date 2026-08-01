package cn.bugstack.ai.trigger.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationQualificationPolicyTest {

    private final ConversationQualificationPolicy policy = new ConversationQualificationPolicy();

    @Test
    void rejectsSingleCharacterTestAndInternalExecutionPlaceholder() {
        assertFalse(policy.shouldAnalyze(List.of(
                message("user", "1"),
                message("assistant", "ai agent execution summary completed!")), 0));
    }

    @Test
    void rejectsLowValueContinuationRepliesEvenWhenThereAreMultipleTurns() {
        assertFalse(policy.shouldAnalyze(List.of(
                message("user", "OK"),
                message("assistant", "你好！有什么可以帮你的？"),
                message("user", "继续"),
                message("assistant", "ai agent execution summary completed!"),
                message("user", "好的")), 0));
    }

    @Test
    void acceptsSubstantiveMultiTurnConversationForQualityObservation() {
        assertTrue(policy.shouldAnalyze(List.of(
                message("user", "请帮我为订单退款异常设计排查方案，需要覆盖接口超时和重复扣款。"),
                message("assistant", "我会先梳理退款链路和幂等性检查点。"),
                message("user", "还要给出告警阈值和人工兜底流程，避免客户资金风险。"),
                message("assistant", "好的，我将补充指标、阈值和升级路径。"),
                message("user", "请按生产故障处理规范输出。")), 0));
    }

    @Test
    void promotesCaseOnlyAfterCrossSessionEvidenceOrExplicitNegativeFeedback() {
        assertFalse(policy.shouldPromoteCase(1, 0, 90, false));
        assertTrue(policy.shouldPromoteCase(2, 0, 80, false));
        assertFalse(policy.shouldPromoteCase(1, 1, 80, false));
    }

    @Test
    void requiresBusinessRelevanceAndEvidenceBeforePromotingCase() {
        assertFalse(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                3, 2, 90, false, 69, 90, false)));
        assertFalse(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                3, 2, 90, false, 90, 59, false)));
        assertFalse(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1, 1, 79, false, 90, 80, false)));
        assertFalse(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1, 1, 80, false, 84, 80, false)));
        assertTrue(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1, 1, 80, false, 90, 80, false)));
        assertTrue(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                2, 0, 80, false, 90, 80, false)));
    }

    @Test
    void criticalRiskStillNeedsStrongEvidenceBeforePromotingCase() {
        assertFalse(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1, 0, 89, true, 95, 90, false)));
        assertFalse(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1, 0, 95, true, 95, 79, false)));
        assertTrue(policy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1, 0, 95, true, 95, 85, false)));
    }

    @Test
    void rejectsTechnicalToolFailureWithoutBusinessEvidence() {
        AnalysisResultParser.CaseCandidate candidate = new AnalysisResultParser.CaseCandidate(
                "MCP 连接失败", "库存反馈工具不可用", "BUG", "HIGH", 90, 95,
                true, true, 95, 95, true, false, "工具连接失败", false, "");

        assertFalse(policy.hasBusinessEvidence(candidate));
    }

    @Test
    void acceptsCaseBackedBySkillOrBusinessMcpEvidence() {
        AnalysisResultParser.CaseCandidate candidate = new AnalysisResultParser.CaseCandidate(
                "库存不足", "SKU 1001 库存低于安全线", "QUALITY", "HIGH", 90, 95,
                false, true, 95, 95, true, false, "库存 Skill 查询结果", true, "SKILL_RESULT");

        assertTrue(policy.hasBusinessEvidence(candidate));
    }

    @Test
    void parsesBusinessEvidenceContract() {
        String json = "{\"signals\":[],\"cases\":[{\"title\":\"库存不足\",\"summary\":\"库存低于安全线\",\"caseType\":\"QUALITY\",\"severity\":\"HIGH\",\"importance\":80,\"confidence\":90,\"criticalRisk\":false,\"businessRelated\":true,\"businessRelevance\":90,\"evidenceScore\":80,\"businessEvidence\":true,\"evidenceSource\":\"skill_result\",\"promoteToCase\":true,\"historicalHighRiskMatch\":false,\"reason\":\"Skill 查询到库存事实\"}]}";

        AnalysisResultParser.CaseCandidate candidate = new AnalysisResultParser().parse(json).cases().get(0);

        assertTrue(candidate.businessEvidence());
        assertEquals("SKILL_RESULT", candidate.evidenceSource());
    }

    private ConversationQualificationPolicy.ConversationMessage message(String role, String content) {
        return new ConversationQualificationPolicy.ConversationMessage(role, content);
    }
}
