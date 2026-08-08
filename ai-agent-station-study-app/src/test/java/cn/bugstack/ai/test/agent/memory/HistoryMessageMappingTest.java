package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.HistoryMessage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HistoryMessageMappingTest {

    @Test
    public void historyMessageCarriesToolPairMetadata() {
        HistoryMessage message = HistoryMessage.builder()
                .role("assistant")
                .content("准备查询库存反馈")
                .toolCallId("call_1")
                .toolName("query_feedback")
                .toolArguments("{\"date\":\"today\"}")
                .toolCallsJson("[{\"id\":\"call_1\"}]")
                .build();

        assertEquals("call_1", message.getToolCallId());
        assertEquals("query_feedback", message.getToolName());
        assertEquals("{\"date\":\"today\"}", message.getToolArguments());
    }
}
