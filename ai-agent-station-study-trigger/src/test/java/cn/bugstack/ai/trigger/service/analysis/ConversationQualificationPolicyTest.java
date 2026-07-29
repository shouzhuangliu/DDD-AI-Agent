package cn.bugstack.ai.trigger.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    private ConversationQualificationPolicy.ConversationMessage message(String role, String content) {
        return new ConversationQualificationPolicy.ConversationMessage(role, content);
    }
}
