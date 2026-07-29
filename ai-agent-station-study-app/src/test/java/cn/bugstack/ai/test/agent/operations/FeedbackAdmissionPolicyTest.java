package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.trigger.service.feedback.FeedbackAdmissionPolicy;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeedbackAdmissionPolicyTest {

    @Test
    public void rejectsTinyTestAndNoiseInputs() {
        FeedbackAdmissionPolicy policy = new FeedbackAdmissionPolicy();

        assertFalse(policy.shouldCapture("1"));
        assertFalse(policy.shouldCapture("hi"));
        assertFalse(policy.shouldCapture("????????????"));
        assertFalse(policy.shouldCapture("测试"));
        assertFalse(policy.shouldCapture("可以"));
    }

    @Test
    public void acceptsClearBusinessIssueReports() {
        FeedbackAdmissionPolicy policy = new FeedbackAdmissionPolicy();

        assertTrue(policy.shouldCapture("我目前遇到了一个问题，你们的 db 有缓存不一致，具体商品是显卡5060"));
        assertTrue(policy.shouldCapture("支付接口一直超时，订单无法完成退款"));
    }

    @Test
    public void acceptsBusinessSupplyGapFeedback() {
        FeedbackAdmissionPolicy policy = new FeedbackAdmissionPolicy();

        assertTrue(policy.shouldCapture("你好我发现咱们业务存在一个空缺商品，具体是一个DDR5的内存，其余我忘记了希望补货哈"));
        assertFalse(policy.shouldCapture("DDR5"));
    }
}
