package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.api.dto.operations.ExplicitFeedbackRequest;
import org.junit.Test;

public class ExplicitFeedbackRequestTest {

    @Test
    public void acceptsFeedbackTargetingAssistantMessage() {
        new ExplicitFeedbackRequest("sess-1", 42L, "THUMBS_DOWN", 1, "answer is stale", "new answer", "user-1")
                .validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsThumbsDownWithoutMessage() {
        new ExplicitFeedbackRequest("sess-1", 42L, "THUMBS_DOWN", 1, "", null, "user-1")
                .validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingAssistantMessage() {
        new ExplicitFeedbackRequest("sess-1", null, "COMMENT", null, "bad", null, "user-1")
                .validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownFeedbackType() {
        new ExplicitFeedbackRequest("sess-1", 42L, "AUTO_CHAT_COPY", null, "bad", null, "user-1")
                .validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRatingOutsideOneToFive() {
        new ExplicitFeedbackRequest("sess-1", 42L, "RATING", 6, null, null, "user-1")
                .validate();
    }
}
