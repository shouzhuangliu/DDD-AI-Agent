package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.FoldConfig;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessageMapper;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import org.junit.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class MemoryFoldingContractTest {

    @Test
    public void foldedHistoryStillBuildsAValidSpringAiToolConversation() {
        List<Map<String, Object>> history = List.of(
                Map.of("role", "user", "content", "query today's inventory feedback"),
                Map.of("role", "assistant", "content", "querying",
                        "tool_calls", List.of(Map.of("id", "call_feedback", "type", "function",
                                "function", Map.of("name", "query_feedback", "arguments", "{\"limit\":20}")))),
                Map.of("role", "tool", "tool_call_id", "call_feedback", "name", "query_feedback",
                        "content", "inventory feedback source".repeat(200)),
                Map.of("role", "assistant", "content", "query completed")
        );

        List<Map<String, Object>> folded = MemoryFoldingPipeline.fold(history, FoldConfig.testProfile());
        List<Message> messages = HistoryMessageMapper.toSpringMessages(folded);

        ToolResponseMessage toolResponse = (ToolResponseMessage) messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .findFirst()
                .orElseThrow();
        String content = toolResponse.getResponses().get(0).responseData();
        assertTrue(content.contains("call_feedback"));
        assertTrue(content.contains("retrieve_tool_call"));
    }
}
