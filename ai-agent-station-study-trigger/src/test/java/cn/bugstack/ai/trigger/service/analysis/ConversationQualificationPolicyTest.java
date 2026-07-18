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
        assertTrue(policy.shouldPromoteCase(2, 0, 70, false));
        assertTrue(policy.shouldPromoteCase(1, 1, 70, false));
    }

    private ConversationQualificationPolicy.ConversationMessage message(String role, String content) {
        return new ConversationQualificationPolicy.ConversationMessage(role, content);
    }
}
