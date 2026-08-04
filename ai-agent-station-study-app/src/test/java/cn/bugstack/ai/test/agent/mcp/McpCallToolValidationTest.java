package cn.bugstack.ai.test.agent.mcp;

import cn.bugstack.ai.domain.agent.service.tools.mcp.McpCallTool;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;

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

    @Test
    public void rejectsMissingRequiredMcpArgumentBeforeRemoteCall() {
        String message = McpCallTool.validateRequiredArguments(
                Map.of("required", java.util.List.of("feedbackId", "decision", "operator")),
                new com.alibaba.fastjson2.JSONObject(Map.of(
                        "feedbackId", "feedback-1",
                        "operator", "inventory-agent")));

        assertEquals("MCP missing required argument(s): decision", message);
    }

    @Test
    public void acceptsCompleteMcpArguments() {
        String message = McpCallTool.validateRequiredArguments(
                Map.of("required", java.util.List.of("feedbackId", "decision", "operator")),
                new com.alibaba.fastjson2.JSONObject(Map.of(
                        "feedbackId", "feedback-1",
                        "decision", "PROMOTE_CASE",
                        "operator", "inventory-agent")));

        assertEquals(null, message);
    }

    @Test
    public void summarizesRequiredArgumentsForProgressiveDisclosure() {
        assertEquals("required: feedbackId, decision, operator",
                McpCallTool.requiredArgumentsSummary(
                        Map.of("required", java.util.List.of("feedbackId", "decision", "operator"))));
    }

    @Test
    public void recognizesTodayFeedbackIntentWithoutConfusingGenericSearch() {
        assertTrue(McpCallTool.isTodayFeedbackQuery("查询今日反馈"));
        assertTrue(McpCallTool.isTodayFeedbackQuery("帮我拉取今天的库存反馈"));
        assertTrue(!McpCallTool.isTodayFeedbackQuery("搜索 DDR5 反馈"));
    }

    @Test
    public void prefersDedicatedTodayFeedbackTool() {
        assertEquals("get_today_feedback",
                McpCallTool.preferredTodayFeedbackTool(Set.of("search_feedback", "get_today_feedback")));
        assertEquals("",
                McpCallTool.preferredTodayFeedbackTool(Set.of("search_feedback")));
    }
}
