package cn.bugstack.ai.trigger.service.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryQueryAdmissionPolicyTest {

    private final MemoryQueryAdmissionPolicy policy = new MemoryQueryAdmissionPolicy();

    @Test
    void rejectsTrivialRecallQueries() {
        assertFalse(policy.shouldRecall("1"));
        assertFalse(policy.shouldRecall("hi"));
        assertFalse(policy.shouldRecall("好的"));
        assertFalse(policy.shouldRecall("   "));
    }

    @Test
    void acceptsBusinessRecallQueries() {
        assertTrue(policy.shouldRecall("DDR5 内存补货"));
        assertTrue(policy.shouldRecall("缓存不一致导致下单异常"));
        assertTrue(policy.shouldRecall("refund rule missing"));
    }

    @Test
    void onlyStoresSubstantialSummaries() {
        assertFalse(policy.shouldStoreSummary("用户已确认"));
        assertTrue(policy.shouldStoreSummary("用户反馈 DDR5 内存商品长期缺货，希望补货并排查供应链规则"));
    }
}
