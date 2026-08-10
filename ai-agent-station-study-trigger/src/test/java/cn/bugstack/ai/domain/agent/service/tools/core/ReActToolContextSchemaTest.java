package cn.bugstack.ai.domain.agent.service.tools.core;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReActToolContextSchemaTest {

    @Test
    void shouldRememberAndReadSchemaOnlyForCurrentMcpAndTool() {
        ReActToolContext context = ReActToolContext.builder()
                .sessionId("session-1")
                .boundMcpIds(List.of("inventory-feedback-mcp"))
                .build();
        McpSchema.Tool tool = mock(McpSchema.Tool.class);

        assertFalse(context.hasMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));

        context.rememberMcpToolSchema(
                " inventory-feedback-mcp ", " get_today_feedback ", tool);

        assertTrue(context.hasMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
        assertSame(tool, context.getMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
        assertFalse(context.hasMcpToolSchema("other-mcp", "get_today_feedback"));
    }
}
