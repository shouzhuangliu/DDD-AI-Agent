package cn.bugstack.ai.domain.agent.service.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingSummaryPolicyTest {

    @Test
    void requiresNewMeaningfulUserTurnsBeforeRollingSummary() {
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 8_000, 24, 4);
        var messages = List.of(
                new RollingSummaryPolicy.MemoryMessage(1, "user", "库存异常：商品缺失"),
                new RollingSummaryPolicy.MemoryMessage(2, "assistant", "已记录"),
                new RollingSummaryPolicy.MemoryMessage(3, "user", "请继续"),
                new RollingSummaryPolicy.MemoryMessage(4, "assistant", "好的"),
                new RollingSummaryPolicy.MemoryMessage(5, "user", "请补充影响范围"),
                new RollingSummaryPolicy.MemoryMessage(6, "assistant", "请提供 SKU"));

        assertFalse(policy.plan(messages, 0).required());
    }

    @Test
    void hardTokenLimitCanForceSummary() {
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 1, 2, 4);
        var messages = List.of(
                new RollingSummaryPolicy.MemoryMessage(1, "user", "这是一段足够长的业务反馈，用于验证硬 token 上限可以绕过回合阈值"),
                new RollingSummaryPolicy.MemoryMessage(2, "assistant", "这是一段足够长的回复，用于验证滚动摘要的硬上限"),
                new RollingSummaryPolicy.MemoryMessage(3, "user", "继续"),
                new RollingSummaryPolicy.MemoryMessage(4, "assistant", "好的"),
                new RollingSummaryPolicy.MemoryMessage(5, "user", "继续"));

        assertTrue(policy.plan(messages, 0).required());
    }

    @Test
    void hardLimitCanTriggerBeforeRecentMessageCount() {
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 10, 20, 24, 4);
        List<RollingSummaryPolicy.MemoryMessage> messages = List.of(
                new RollingSummaryPolicy.MemoryMessage(1, "user", "这是一段非常长的库存反馈".repeat(20)),
                new RollingSummaryPolicy.MemoryMessage(2, "assistant", "已记录并准备分析"),
                new RollingSummaryPolicy.MemoryMessage(3, "user", "请继续"));

        RollingSummaryPolicy.SummaryPlan plan = policy.plan(messages, 0);

        assertTrue(plan.required());
        org.junit.jupiter.api.Assertions.assertEquals(1, plan.startMessageId());
        org.junit.jupiter.api.Assertions.assertEquals(1, plan.endMessageId());
        org.junit.jupiter.api.Assertions.assertEquals(2, plan.recentStartMessageId());
    }

    @Test
    void doesNotSummarizeWhenThereIsNoOlderMessageToFold() {
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 1, 2, 24, 0);

        assertFalse(policy.plan(List.of(
                new RollingSummaryPolicy.MemoryMessage(1, "user", "很长".repeat(100))), 0).required());
    }
}
