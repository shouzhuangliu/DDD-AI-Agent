package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.trigger.service.observability.ConversationTraceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTraceTest {

    @Test
    void exposesAgentSessionTraceEndpoint() {
        ConversationTraceService traceService = mock(ConversationTraceService.class);
        ConversationTraceService.ConversationTrace expected = new ConversationTraceService.ConversationTrace(
                "cs",
                "sess-1",
                new ConversationTraceService.TraceSummary(1, 1, 0, 0, 0, false),
                List.of()
        );
        when(traceService.trace("cs", "sess-1")).thenReturn(expected);
        DashboardController controller = new DashboardController();
        ReflectionTestUtils.setField(controller, "conversationTraceService", traceService);

        ConversationTraceService.ConversationTrace result = controller.conversationTrace("cs", "sess-1");

        assertEquals(expected, result);
    }
}
