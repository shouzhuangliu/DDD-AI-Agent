package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.RollingSummaryPolicy;
import cn.bugstack.ai.domain.agent.service.memory.TokenBudgetEstimator;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class RollingSummaryPolicyTest {

    @Test
    public void estimatesCjkAndLatinTokensWithoutCharacterBudget() {
        TokenBudgetEstimator estimator = new TokenBudgetEstimator();
        assertEquals(4, estimator.estimate("企业记忆"));
        assertEquals(2, estimator.estimate("abcdefgh"));
    }

    @Test
    public void plansOlderRangeAndKeepsRecentMessages() {
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 8, 2);
        List<RollingSummaryPolicy.MemoryMessage> messages = List.of(
                new RollingSummaryPolicy.MemoryMessage(1, "user", "企业记忆"),
                new RollingSummaryPolicy.MemoryMessage(2, "assistant", "企业记忆"),
                new RollingSummaryPolicy.MemoryMessage(3, "user", "企业记忆"),
                new RollingSummaryPolicy.MemoryMessage(4, "assistant", "企业记忆"));

        RollingSummaryPolicy.SummaryPlan plan = policy.plan(messages, 0);

        assertTrue(plan.required());
        assertEquals(1, plan.startMessageId());
        assertEquals(2, plan.endMessageId());
        assertEquals(3, plan.recentStartMessageId());
    }

    @Test
    public void doesNotResummarizeCoveredMessages() {
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), 8, 2);
        List<RollingSummaryPolicy.MemoryMessage> messages = List.of(
                new RollingSummaryPolicy.MemoryMessage(1, "user", "企业记忆"),
                new RollingSummaryPolicy.MemoryMessage(2, "assistant", "企业记忆"),
                new RollingSummaryPolicy.MemoryMessage(3, "user", "ok"));

        assertFalse(policy.plan(messages, 2).required());
    }
}
