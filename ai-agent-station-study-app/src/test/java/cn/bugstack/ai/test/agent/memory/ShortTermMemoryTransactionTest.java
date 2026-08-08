package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryService;
import org.junit.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.Assert.assertNotNull;

public class ShortTermMemoryTransactionTest {

    @Test
    public void refreshIfNeededRunsInsideTransaction() throws NoSuchMethodException {
        assertNotNull(ShortTermMemoryService.class
                .getMethod("refreshIfNeeded", String.class, String.class, String.class)
                .getAnnotation(Transactional.class));
    }
}
