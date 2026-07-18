package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.api.dto.operations.ManualFeedbackRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ManualFeedbackRequestTest {

    @Test
    public void acceptsOperationsFeedbackWithoutAssistantMessage() {
        ManualFeedbackRequest request = new ManualFeedbackRequest(
                "OPERATIONS", "ISSUE_REPORT", 1, "支付回调 Agent 近期多次漏查超时订单", "order-timeout", "ops-1");

        request.validate();

        assertEquals("OPERATIONS", request.normalizedSourceType());
        assertEquals("ISSUE_REPORT", request.normalizedFeedbackType());
        assertEquals("ops-1", request.normalizedSubmittedBy());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownSourceType() {
        new ManualFeedbackRequest("AI_INFERRED", "COMMENT", null, "观察到异常", "", "bot")
                .validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankMessage() {
        new ManualFeedbackRequest("TEST", "ISSUE_REPORT", null, " ", "qa", "tester")
                .validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRatingOutsideOneToFive() {
        new ManualFeedbackRequest("USER", "RATING", 6, "不好用", "", "user")
                .validate();
    }
}
