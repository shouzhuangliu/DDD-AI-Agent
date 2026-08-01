package cn.bugstack.ai.test.agent.react;

import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActResponseHandlingTest {

    @Test
    void explainsWhenModelReturnsEmptyText() {
        String message = ReActExecuteStrategy.normalizeFinalContent("", "2001");

        assertTrue(message.contains("2001"));
        assertTrue(message.contains("空"));
    }

    @Test
    void identifiesRateLimitResponsesWithoutRetrying() {
        assertTrue(ReActExecuteStrategy.isRateLimitError("429 - rpm exhausted"));
        assertTrue(ReActExecuteStrategy.isRateLimitError("quota_exceeded_error"));
    }
}
