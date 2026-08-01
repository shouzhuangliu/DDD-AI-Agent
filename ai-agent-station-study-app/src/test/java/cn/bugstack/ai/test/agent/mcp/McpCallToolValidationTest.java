package cn.bugstack.ai.test.agent.mcp;

import cn.bugstack.ai.domain.agent.service.tools.mcp.McpCallTool;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class McpCallToolValidationTest {

    @Test
    public void rejectsToolThatIsNotExposedByMcpServer() {
        String message = McpCallTool.validateToolName(
                Set.of("get_today_feedback", "get_feedback_detail"), "ping");
        assertTrue(message.contains("ping"));
        assertTrue(message.contains("get_today_feedback"));
        assertTrue(message.contains("get_feedback_detail"));
    }

    @Test
    public void acceptsToolExposedByMcpServer() {
        assertEquals(null,
                McpCallTool.validateToolName(Set.of("get_today_feedback"), "get_today_feedback"));
    }
}
