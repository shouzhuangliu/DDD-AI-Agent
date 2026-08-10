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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolDiscoveryToolTest {

    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final McpSchema.ListToolsResult listToolsResult = mock(McpSchema.ListToolsResult.class);
    private McpToolDiscoveryTool tool;

    @BeforeEach
    void setUp() {
        tool = new McpToolDiscoveryTool();
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
    void returnsAtMostThreeMatchingCandidatesWithHandles() {
        when(applicationContext.getBean(anyString(), eq(McpSyncClient.class))).thenReturn(client);
        when(client.listTools()).thenReturn(listToolsResult);
        List<McpSchema.Tool> tools = List.of(
                tool("get_feedback_detail", "查询一条库存反馈详情"),
                tool("get_today_feedback", "查询今日库存业务反馈"),
                tool("mark_feedback_triaged", "记录库存反馈分诊结果"),
                tool("get_stock_snapshot", "查询库存快照"));
        when(listToolsResult.tools()).thenReturn(tools);

        String result = tool.discoverMcpTools("查询今日库存反馈", "inventory-feedback-mcp", 10);

        assertTrue(result.contains("get_today_feedback"));
        assertTrue(result.contains("toolHandle"));
        assertEquals(3, ReActToolContextHolder.get().getMcpToolHandles().size());
    }

    @Test
    void rejectsAnUnboundMcpBeforeClientLookup() {
        String result = tool.discoverMcpTools("查询反馈", "other-mcp", 3);

        assertTrue(result.contains("MCP_NOT_BOUND"));
    }

    @Test
    void returnsNotFoundInsteadOfGuessingWhenNoToolMatches() {
        when(applicationContext.getBean(anyString(), eq(McpSyncClient.class))).thenReturn(client);
        when(client.listTools()).thenReturn(listToolsResult);
        McpSchema.Tool snapshot = tool("get_stock_snapshot", "查询库存快照");
        when(listToolsResult.tools()).thenReturn(List.of(snapshot));

        String result = tool.discoverMcpTools("发送邮件", "inventory-feedback-mcp", 3);

        assertTrue(result.contains("MCP_TOOL_NOT_FOUND"));
        assertTrue(ReActToolContextHolder.get().getMcpToolHandles().isEmpty());
    }

    private McpSchema.Tool tool(String name, String description) {
        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn(description);
        when(tool.inputSchema()).thenReturn(null);
        return tool;
    }
}
