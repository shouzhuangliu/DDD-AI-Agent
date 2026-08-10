package cn.bugstack.ai.domain.agent.service.tools.mcp;

import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolHandleCallToolTest {

    private final McpCallTool delegate = mock(McpCallTool.class);
    private McpToolHandleCallTool tool;

    @BeforeEach
    void setUp() {
        tool = new McpToolHandleCallTool();
        ReflectionTestUtils.setField(tool, "mcpCallTool", delegate);
        ReActToolContextHolder.set(ReActToolContext.builder()
                .sessionId("session-1")
                .agentId("inventory-agent")
                .boundMcpIds(List.of("inventory-feedback-mcp"))
                .build());
    }

    @AfterEach
    void tearDown() {
        ReActToolContextHolder.clear();
    }

    @Test
    void rejectsUnknownHandleWithoutCallingDelegate() {
        String result = tool.callMcpToolByHandle("missing", "{}");

        assertTrue(result.contains("MCP_TOOL_HANDLE_REJECTED"));
        verify(delegate, never()).callMcpToolByHandle("missing", "{}");
    }

    @Test
    void rejectsExpiredHandleWithoutCallingDelegate() {
        ReActToolContext context = ReActToolContextHolder.get();
        McpSchema.Tool exposedTool = mock(McpSchema.Tool.class);
        context.rememberMcpToolHandle("expired", new ReActToolContext.McpToolHandleBinding(
                "expired", "inventory-agent", "session-1", "inventory-feedback-mcp",
                "get_today_feedback", "sha256:test", System.currentTimeMillis() - 1, exposedTool));

        String result = tool.callMcpToolByHandle("expired", "{}");

        assertTrue(result.contains("MCP_TOOL_HANDLE_EXPIRED"));
        verify(delegate, never()).callMcpToolByHandle("expired", "{}");
    }

    @Test
    void delegatesAValidConversationHandle() {
        ReActToolContext context = ReActToolContextHolder.get();
        McpSchema.Tool exposedTool = mock(McpSchema.Tool.class);
        context.rememberMcpToolHandle("valid", new ReActToolContext.McpToolHandleBinding(
                "valid", "inventory-agent", "session-1", "inventory-feedback-mcp",
                "get_today_feedback", "sha256:test", System.currentTimeMillis() + 60_000, exposedTool));
        when(delegate.callMcpToolByHandle("valid", "{}"))
                .thenReturn("feedback-result");

        assertEquals("feedback-result", tool.callMcpToolByHandle("valid", "{}"));
        verify(delegate).callMcpToolByHandle("valid", "{}");
    }
}
