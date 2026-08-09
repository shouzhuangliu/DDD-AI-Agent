package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.HistoryMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolCallPersistenceContractTest {

    @Test
    public void assistantToolCallsMapKeepsIdsNamesAndArgumentsForArchive() {
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("call-1", "function", "get_today_feedback", "{\"limit\":10}")));

        Map<String, Object> mapped = HistoryMessageMapper.toMap(assistant);
        List<?> calls = (List<?>) mapped.get("tool_calls");
        Map<?, ?> call = (Map<?, ?>) calls.get(0);
        Map<?, ?> function = (Map<?, ?>) call.get("function");

        assertEquals("call-1", call.get("id"));
        assertEquals("get_today_feedback", function.get("name"));
        assertEquals("{\"limit\":10}", function.get("arguments"));
        assertTrue(HistoryMessageMapper.toolMap("call-1", "get_today_feedback", "result")
                .containsValue("call-1"));
    }
}
