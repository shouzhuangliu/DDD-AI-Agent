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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolSchemaToolTest {

    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final McpSchema.ListToolsResult listToolsResult = mock(McpSchema.ListToolsResult.class);
    private final McpSchema.Tool exposedTool = mock(McpSchema.Tool.class);
    private McpToolSchemaTool tool;

    @BeforeEach
    void setUp() {
        tool = new McpToolSchemaTool();
        ReflectionTestUtils.setField(tool, "applicationContext", applicationContext);
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
    void shouldRejectSchemaLookupWhenMcpIsNotBound() {
        String result = tool.getMcpToolSchema("other-mcp", "get_today_feedback");

        assertTrue(result.contains("MCP 未绑定"));
    }

    @Test
    void shouldReturnSchemaAndRememberItWhenToolIsExposed() {
        when(applicationContext.getBean(anyString(), eq(McpSyncClient.class))).thenReturn(client);
        when(client.listTools()).thenReturn(listToolsResult);
        when(listToolsResult.tools()).thenReturn(List.of(exposedTool));
        when(exposedTool.name()).thenReturn("get_today_feedback");
        when(exposedTool.description()).thenReturn("查询今日库存业务反馈");
        when(exposedTool.inputSchema()).thenReturn(null);
        String result = tool.getMcpToolSchema(
                "inventory-feedback-mcp", "get_today_feedback");

        assertTrue(result.contains("get_today_feedback"));
        assertTrue(result.contains("查询今日库存业务反馈"));
        assertSame(exposedTool, ReActToolContextHolder.get()
                .getMcpToolSchema("inventory-feedback-mcp", "get_today_feedback"));
    }
}
