package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryService;
import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ShortTermMemoryTransactionTest {

    @Test
    public void modelRefreshDoesNotHoldTransactionWhilePersistenceDoes() throws NoSuchMethodException {
        assertNull(ShortTermMemoryService.class
                .getMethod("refreshIfNeeded", String.class, String.class, String.class)
                .getAnnotation(Transactional.class));
        assertNotNull(ShortTermMemoryPersistenceService.class
                .getMethod("saveIfUnchanged", String.class, String.class, String.class,
                        cn.bugstack.ai.infrastructure.dao.po.MemorySummary.class,
                        ShortTermMemoryPersistenceService.RollingSummarySnapshot.class,
                        com.alibaba.fastjson2.JSONObject.class, String.class)
                .getAnnotation(Transactional.class));
    }
}
