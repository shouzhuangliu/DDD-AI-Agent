package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ShortTermMemoryBoundaryTest {

    @Test
    void shortTermSummaryServiceMustNotDependOnLongTermMemoryStorage() {
        boolean hasLongTermMemoryDependency = Arrays.stream(ShortTermMemoryService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(LongTermMemoryPort.class));

        assertFalse(hasLongTermMemoryDependency,
                "会话滚动摘要只能写入 MySQL，不能直接写入正式长期记忆");
    }
}
