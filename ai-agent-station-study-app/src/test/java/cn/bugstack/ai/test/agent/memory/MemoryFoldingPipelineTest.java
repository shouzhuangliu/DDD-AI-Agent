package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.FoldConfig;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessageSanitizer;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemoryFoldingPipelineTest {

    @Test
    public void foldsOldToolResultButKeepsRetrievalPointer() {
        List<Map<String, Object>> input = List.of(
                Map.of("role", "user", "content", "查询库存反馈"),
                Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                        "id", "call_old", "type", "function",
                        "function", Map.of("name", "query_feedback", "arguments", "{\"date\":\"today\"}")))),
                Map.of("role", "tool", "tool_call_id", "call_old", "name", "query_feedback",
                        "content", "x".repeat(1000)),
                Map.of("role", "assistant", "content", "当前结论")
        );

        List<Map<String, Object>> output = MemoryFoldingPipeline.fold(input, FoldConfig.testProfile());

        String toolContent = String.valueOf(output.get(2).get("content"));
        assertTrue(toolContent.contains("call_old"));
        assertTrue(toolContent.contains("retrieve_tool_call"));
        assertFalse(toolContent.equals("x".repeat(1000)));
    }

    @Test
    public void sanitizeDropsOrphanToolAndIncompleteAssistantPair() {
        List<Map<String, Object>> output = HistoryMessageSanitizer.sanitize(List.of(
                Map.of("role", "tool", "tool_call_id", "missing", "content", "orphan"),
                Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call_missing")))
        ));

        assertTrue(output.stream().noneMatch(message -> "tool".equals(message.get("role"))));
        assertTrue(output.stream().noneMatch(message -> message.containsKey("tool_calls")));
    }
}
