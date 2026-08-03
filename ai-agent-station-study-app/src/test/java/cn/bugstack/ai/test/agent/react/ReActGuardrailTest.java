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
    void limitsToolCallsIndependentlyFromThirtyStepSafetyLimit() {
        ReActToolContext context = ReActToolContext.builder().maxSteps(30).maxToolCalls(10).build();

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
}
