package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.ToolFoldConfig;
import cn.bugstack.ai.domain.agent.service.memory.ToolResultFoldingPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultFoldingPolicyTest {

    @Test
    void oldToolExchangeIsFoldedIndependentlyAndMarkedHistorical() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "查询库存"));
        messages.add(assistantCall("call-old", "get_today_feedback"));
        messages.add(tool("call-old", "短结果"));

        boolean folded = ToolResultFoldingPolicy.foldExchange(messages, 1, ToolFoldConfig.testProfile());

        assertTrue(folded);
        Map<String, Object> result = messages.get(2);
        assertTrue(String.valueOf(result.get("content")).contains("retrieve_tool_call"));
        assertTrue(String.valueOf(result.get("content")).contains("历史"));
        assertTrue(Boolean.TRUE.equals(result.get("tool_result_folded")));
        assertTrue("HISTORICAL".equals(result.get("tool_result_freshness")));
    }

    @Test
    void currentShortToolResultIsNotFoldedByMessageBudgetPolicy() {
        Map<String, Object> message = tool("call-current", "短结果");

        boolean folded = ToolResultFoldingPolicy.foldSingleToolResult(message, ToolFoldConfig.testProfile());

        assertFalse(folded);
        assertFalse(message.containsKey("tool_result_folded"));
        assertTrue("短结果".equals(message.get("content")));
    }

    private static Map<String, Object> assistantCall(String id, String name) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", "{}");
        return new LinkedHashMap<>(Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", id, "type", "function", "function", function))));
    }

    private static Map<String, Object> tool(String id, String content) {
        return new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", id,
                "name", "get_today_feedback", "content", content));
    }
}
