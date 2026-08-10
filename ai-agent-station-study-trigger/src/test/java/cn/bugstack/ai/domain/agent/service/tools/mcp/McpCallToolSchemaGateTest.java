package cn.bugstack.ai.domain.agent.service.tools.mcp;

import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpCallToolSchemaGateTest {

    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final McpSchema.ListToolsResult listToolsResult = mock(McpSchema.ListToolsResult.class);
    private final McpSchema.Tool exposedTool = mock(McpSchema.Tool.class);
    private final McpSchema.CallToolResult callToolResult = mock(McpSchema.CallToolResult.class);
    private McpCallTool tool;

    @BeforeEach
    void setUp() {
        tool = new McpCallTool();
        ReflectionTestUtils.setField(tool, "applicationContext", applicationContext);
        when(applicationContext.getBean(anyString(), eq(McpSyncClient.class))).thenReturn(client);
        when(client.listTools()).thenReturn(listToolsResult);
        when(listToolsResult.tools()).thenReturn(List.of(exposedTool));
        when(exposedTool.name()).thenReturn("get_today_feedback");
        when(exposedTool.inputSchema()).thenReturn(null);
        when(callToolResult.content()).thenReturn(List.of());
        when(callToolResult.isError()).thenReturn(false);
        when(client.callTool(any())).thenReturn(callToolResult);
        ReActToolContextHolder.set(ReActToolContext.builder()
                .sessionId("session-1")
                .agentId("inventory-agent")
                .userMessage("查询库存反馈")
                .boundMcpIds(List.of("inventory-feedback-mcp"))
                .build());
    }

    @AfterEach
    void tearDown() {
        ReActToolContextHolder.clear();
    }

    @Test
    void shouldBlockCallBeforeSchemaLookup() {
        String result = tool.callMcpTool(
                "inventory-feedback-mcp", "get_today_feedback", "{}");

        assertTrue(result.contains("MCP_SCHEMA_REQUIRED"));
        verify(client, never()).callTool(any());
    }

    @Test
    void shouldCallMcpAfterSchemaWasLoaded() {
        ReActToolContextHolder.get().rememberMcpToolSchema(
                "inventory-feedback-mcp", "get_today_feedback", exposedTool);

        String result = tool.callMcpTool(
                "inventory-feedback-mcp", "get_today_feedback", "{\"limit\":20}");

        verify(client).callTool(any());
        assertTrue(result.contains("MCP 返回空内容"));
    }
}
