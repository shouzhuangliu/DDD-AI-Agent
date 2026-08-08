package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.ToolCallExchange;
import cn.bugstack.ai.domain.agent.service.tools.memory.RetrieveToolCallTool;
import org.junit.After;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RetrieveToolCallToolTest {

    private final ChatMessageRecorder recorder = mock(ChatMessageRecorder.class);
    private final RetrieveToolCallTool tool = new RetrieveToolCallTool();

    public RetrieveToolCallToolTest() {
        ReflectionTestUtils.setField(tool, "recorder", recorder);
    }

    @After
    public void clearContext() {
        cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder.clear();
    }

    @Test
    public void retrievesOnlyFromCurrentSession() {
        when(recorder.findToolExchange("session_a", "call_1"))
                .thenReturn(new ToolCallExchange("session_a", "call_1", "query_feedback", "{}", "", "full result"));

        String result = tool.retrieveToolCall("session_b", "call_1");

        assertTrue(result.contains("no tool call exchange"));
        verify(recorder).findToolExchange("session_b", "call_1");
    }

    @Test
    public void retrievesExchangeWithoutExecutingOriginalToolAgain() {
        when(recorder.findToolExchange("session_a", "call_1"))
                .thenReturn(new ToolCallExchange("session_a", "call_1", "query_feedback", "{}", "", "full result"));

        String result = tool.retrieveToolCall("session_a", "call_1");

        assertTrue(result.contains("full result"));
        verify(recorder).findToolExchange("session_a", "call_1");
    }
}
