package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.ContextBudgetPolicy;
import cn.bugstack.ai.domain.agent.service.memory.ModelContextProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextBudgetPolicyTest {

    @Test
    public void usesModelWindowRatiosAndReservesOutputTokens() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(
                Map.of("model-32k", new ModelContextProfile(32_768, 4_096, 0.60d, 0.85d, 512)),
                new ModelContextProfile(16_384, 2_048, 0.60d, 0.85d, 512));

        ContextBudgetPolicy.BudgetDecision decision = policy.decide(
                "model-32k", "system prompt", "tool schema", List.of(message("你好")));

        assertEquals(32_768 - 4_096 - 512, decision.effectiveInputTokens());
        assertEquals((int) Math.floor(decision.effectiveInputTokens() * 0.60d), decision.softSummaryThreshold());
        assertEquals((int) Math.floor(decision.effectiveInputTokens() * 0.85d), decision.hardFoldThreshold());
        assertFalse(decision.shouldFold());
    }

    @Test
    public void unknownModelUsesSafeDefaultAndTriggersOnlyWhenWindowIsActuallyFull() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(Map.of(),
                new ModelContextProfile(1_024, 128, 0.60d, 0.85d, 64));
        String longText = "x".repeat(7_000);

        ContextBudgetPolicy.BudgetDecision decision = policy.decide(
                "unknown", "system", "", List.of(message(longText)));

        assertEquals(832, decision.effectiveInputTokens());
        assertTrue(decision.currentInputTokens() > decision.hardFoldThreshold());
        assertTrue(decision.shouldFold());
    }

    @Test
    public void modelLookupIsCaseInsensitiveAndCountsToolCallArguments() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(
                Map.of("SenseNova-6.7-Flash-Lite", new ModelContextProfile(2_048, 256, 0.60d, 0.85d, 64)),
                ModelContextProfile.safeDefault());
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "ok");
        message.put("tool_calls", List.of(Map.of("id", "call-1", "function", Map.of(
                "name", "get_today_feedback", "arguments", "x".repeat(2_000)))));

        ContextBudgetPolicy.BudgetDecision decision = policy.decide(
                "sensenova-6.7-flash-lite", "", "", List.of(message));

        assertEquals(1_728, decision.effectiveInputTokens());
        assertTrue(decision.currentInputTokens() > 400);
    }

    private static Map<String, Object> message(String content) {
        return Map.of("role", "user", "content", content);
    }
}
