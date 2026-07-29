package cn.bugstack.ai.trigger.service.feedback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackAdmissionPolicyTest {

    private final FeedbackAdmissionPolicy policy = new FeedbackAdmissionPolicy();

    @Test
    void rejectsTinyOrTestNoise() {
        assertFalse(policy.shouldCapture("1"));
        assertFalse(policy.shouldCapture("hi"));
        assertFalse(policy.shouldCapture("测试"));
    }

    @Test
    void capturesConcreteBusinessIssueFeedback() {
        assertTrue(policy.shouldCapture("你好我发现咱们业务存在一个空缺商品，具体是一个DDR5的内存，希望补货"));
        assertTrue(policy.shouldCapture("你们的db缓存不一致，商品显卡5060下单后列表还是没货"));
    }

    @Test
    void rejectsPureGreetingWithoutBusinessIssue() {
        assertFalse(policy.shouldCapture("你好呀，辛苦了"));
        assertFalse(policy.shouldCapture("收到，可以的"));
    }
}
