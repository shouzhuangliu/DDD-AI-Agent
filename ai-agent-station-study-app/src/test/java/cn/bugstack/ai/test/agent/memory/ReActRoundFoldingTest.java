package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.ContextBudgetPolicy;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import cn.bugstack.ai.domain.agent.service.memory.ModelContextProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReActRoundFoldingTest {

    @Test
    public void eachRoundCanFoldOldToolResultsWithoutDuplicatingCurrentUser() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "查询今天库存反馈"));
        messages.add(assistantCall("call-old"));
        messages.add(tool("call-old", "历史工具结果".repeat(500)));
        messages.add(message("assistant", "已完成第一步"));
        messages.add(message("user", "继续汇总"));
        messages.add(assistantCall("call-current"));
        messages.add(tool("call-current", "当前结果"));

        ContextBudgetPolicy policy = new ContextBudgetPolicy(Map.of(),
                new ModelContextProfile(1_024, 128, 0.60d, 0.85d, 64));
        List<Map<String, Object>> folded = MemoryFoldingPipeline.fold(messages,
                policy.decide("model", "system", "tool", messages));

        assertEquals(2, folded.stream().filter(m -> "user".equals(m.get("role"))).count());
        assertTrue(folded.stream().filter(m -> "tool".equals(m.get("role")))
                .map(m -> String.valueOf(m.get("content")))
                .anyMatch(content -> content.contains("retrieve_tool_call")));
        assertTrue(folded.stream().anyMatch(m -> "call-current".equals(m.get("tool_call_id"))));
    }

    private static Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private static Map<String, Object> assistantCall(String id) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_today_feedback");
        function.put("arguments", "{}");
        return Map.of("role", "assistant", "content", "", "tool_calls",
                List.of(Map.of("id", id, "type", "function", "function", function)));
    }

    private static Map<String, Object> tool(String id, String content) {
        return Map.of("role", "tool", "tool_call_id", id, "name", "get_today_feedback", "content", content);
    }
}
