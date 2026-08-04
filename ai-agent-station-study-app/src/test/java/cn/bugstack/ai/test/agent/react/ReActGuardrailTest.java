package cn.bugstack.ai.test.agent.react;

import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActGuardrailTest {

    @Test
    void limitsRepeatedToolCallsAfterTwoIdenticalAttempts() {
        ReActToolContext context = ReActToolContext.builder().build();

        assertTrue(!context.repeatedCallExceeded("query_feedback", "same arguments"));
        assertTrue(context.repeatedCallExceeded("query_feedback", "same arguments"));
    }

    @Test
    void stopsAtConfiguredSafetyStepsNotLegacyToolCallLimit() {
        ReActToolContext context = ReActToolContext.builder().maxSteps(10).maxToolCalls(1).build();

        for (int i = 0; i < 10; i++) {
            assertTrue(context.consumeStep() > 0);
        }
        assertEquals(-1, context.consumeStep());
    }

    @Test
    void classifiesUnavailableToolsAsFailures() {
        assertTrue(ReActExecuteStrategy.isToolFailureResult("未知工具: ping"));
        assertTrue(ReActExecuteStrategy.isToolFailureResult("MCP 调用异常: connection refused"));
    }

    @Test
    void treatsBareContinuationAsClarificationRequest() {
        assertTrue(ReActExecuteStrategy.isLowValueRequest("1"));
        assertTrue(ReActExecuteStrategy.isLowValueRequest("OK"));
    }

    @Test
    void doesNotUseTheLegacyTenCallValueAsBusinessFrequencyLimit() {
        ReActToolContext context = ReActToolContext.builder().maxSteps(30).maxToolCalls(1).build();

        assertTrue(context.consumeStep() > 0);
        assertTrue(context.consumeStep() > 0);
    }

    @Test
    void blocksOnlyDuplicateCallsInsideTheSlidingWindow() {
        ReActToolContext context = ReActToolContext.builder().repeatWindowSize(2).build();

        assertTrue(!context.repeatedCallExceeded("query_feedback", "{\"limit\":10}"));
        assertTrue(context.repeatedCallExceeded("query_feedback", "{\"limit\":10}"));
        assertTrue(!context.repeatedCallExceeded("query_feedback", "{\"limit\":20}"));
        assertTrue(!context.repeatedCallExceeded("query_cases", "{\"limit\":10}"));
        assertTrue(!context.repeatedCallExceeded("query_feedback", "{\"limit\":10}"));
    }

    @Test
    void rejectsUnknownToolsBeforeExecution() {
        ReActToolContext context = ReActToolContext.builder().userMessage("查询今日反馈").build();

        ReActToolContext.ToolCallDecision decision = context.admitToolCall("ping", "{}", false);

        assertEquals("UNKNOWN_TOOL", decision.code());
        assertTrue(!decision.allowed());
    }

    @Test
    void rejectsKnownButUnboundToolsBeforeExecution() {
        ReActToolContext context = ReActToolContext.builder().userMessage("帮我排查项目代码").build();

        ReActToolContext.ToolCallDecision decision = context.admitToolCall("run_bash", "ls", true, false);

        assertEquals("UNAUTHORIZED_TOOL", decision.code());
        assertTrue(!decision.allowed());
    }

    @Test
    void blocksExcessiveCallsOfOneToolButAllowsDifferentArgumentsFirst() {
        ReActToolContext context = ReActToolContext.builder()
                .userMessage("查询今日反馈")
                .repeatWindowSize(4)
                .sameToolWindowLimit(2)
                .build();

        assertTrue(context.admitToolCall("call_mcp_tool", "{\"limit\":10}", true).allowed());
        assertTrue(context.admitToolCall("call_mcp_tool", "{\"limit\":20}", true).allowed());
        ReActToolContext.ToolCallDecision decision = context.admitToolCall("call_mcp_tool", "{\"limit\":30}", true);

        assertEquals("TOOL_FREQUENCY", decision.code());
        assertTrue(!decision.allowed());
    }

    @Test
    void blocksProjectToolsForAPlainFeedbackMessage() {
        ReActToolContext context = ReActToolContext.builder().userMessage("我反馈库存不一致").build();

        ReActToolContext.ToolCallDecision decision = context.admitToolCall("run_bash", "ls", true);

        assertEquals("INTENT_NOT_ALLOWED", decision.code());
        assertTrue(!decision.allowed());
    }
}
