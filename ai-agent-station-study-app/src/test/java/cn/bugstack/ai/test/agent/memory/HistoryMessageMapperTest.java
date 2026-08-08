package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.HistoryMessageMapper;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistoryMessageMapperTest {

    @Test
    public void mapsAssistantToolCallAndToolResponseWithoutDroppingIds() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                        "id", "call_1", "type", "function",
                        "function", Map.of("name", "query_feedback", "arguments", "{}")))),
                Map.of("role", "tool", "tool_call_id", "call_1", "name", "query_feedback", "content", "ok")
        );

        List<Message> mapped = HistoryMessageMapper.toSpringMessages(messages);

        assertTrue(mapped.get(0) instanceof AssistantMessage);
        assertEquals("call_1", ((AssistantMessage) mapped.get(0)).getToolCalls().get(0).id());
        assertTrue(mapped.get(1) instanceof ToolResponseMessage);
        assertEquals("call_1", ((ToolResponseMessage) mapped.get(1)).getResponses().get(0).id());
    }
}
