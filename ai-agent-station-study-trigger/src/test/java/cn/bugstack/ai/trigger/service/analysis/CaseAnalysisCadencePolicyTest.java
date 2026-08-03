package cn.bugstack.ai.trigger.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseAnalysisCadencePolicyTest {

    private final CaseAnalysisCadencePolicy policy = new CaseAnalysisCadencePolicy();

    @Test
    void doesNotReevaluateWithoutTwoNewBusinessEvidenceMessages() {
        var messages = List.of(
                message("user", "库存缺失，商品 DDR5 无法下单", 1),
                message("assistant", "已记录", 2));
        var cursor = new CaseAnalysisCadencePolicy.EvaluationCursor(1, 1, "fp-1");

        assertFalse(policy.shouldEvaluate(messages, cursor, false, false).required());
    }

    @Test
    void explicitFeedbackCanEvaluateEarlyButStillUsesEvidenceGateLater() {
        var result = policy.shouldEvaluate(
                List.of(message("user", "商品缺失", 1)),
                new CaseAnalysisCadencePolicy.EvaluationCursor(0, 0, ""), true, false);

        assertTrue(result.required());
    }

    @Test
    void ignoresLowValueMessagesWhenCountingEvidence() {
        assertTrue(policy.countMeaningfulUserTurns(List.of(
                message("user", "1", 1),
                message("user", "OK", 2),
                message("user", "库存接口返回 DDR5 缺货，用户无法下单", 3))) == 1);
    }

    private CaseAnalysisCadencePolicy.ConversationMessage message(String role, String content, long id) {
        return new CaseAnalysisCadencePolicy.ConversationMessage(id, role, content);
    }
}
