package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.ToolCallExchange;
import cn.bugstack.ai.domain.agent.service.tools.memory.RetrieveToolCallTool;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RetrieveToolCallPaginationTest {

    @Test
    public void returnsBoundedPageAndContinuationOffset() {
        ChatMessageRecorder recorder = mock(ChatMessageRecorder.class);
        RetrieveToolCallTool tool = new RetrieveToolCallTool();
        ReflectionTestUtils.setField(tool, "recorder", recorder);
        when(recorder.findToolExchange("s-1", "call-1"))
                .thenReturn(new ToolCallExchange("s-1", "call-1", "get_today_feedback", "{}", "",
                        "0123456789".repeat(3_000)));

        String page = tool.retrieveToolCallPage("s-1", "call-1", 10_000, 5_000);

        assertTrue(page.contains("\"content\":"));
        assertTrue(page.contains("\"hasMore\":true"));
        assertTrue(page.contains("\"nextOffset\":15000"));
        assertTrue(page.contains("\"originalChars\":30000"));
    }
}
