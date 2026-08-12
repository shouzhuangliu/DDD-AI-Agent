package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryLifecycleServiceTest {
    private final IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
    private final IAgentMemoryChangeLogDao changeLogDao = mock(IAgentMemoryChangeLogDao.class);
    private final IAgentMemoryIndexOutboxDao outboxDao = mock(IAgentMemoryIndexOutboxDao.class);
    private final AgentMemoryLifecycleService service = new AgentMemoryLifecycleService(cardDao, changeLogDao, outboxDao);

    @Test
    void sameIdentityUpdatesStableMemoryIdAndIncrementsVersion() {
        when(cardDao.queryActiveByIdentity("inventory", "BUSINESS_RULE", "inventory.stock-threshold"))
                .thenReturn(AgentMemoryCard.builder().memoryId("mem-1").agentId("inventory").version(2).build());
        var result = service.upsert(command());
        assertEquals("mem-1", result.memoryId());
        assertEquals(3, result.version());
        assertEquals("UPDATE", result.operation());
    }

    @Test
    void retireSoftDeletesCardAndQueuesVectorDelete() {
        when(cardDao.queryActiveByMemoryId("inventory", "mem-1"))
                .thenReturn(AgentMemoryCard.builder().memoryId("mem-1").agentId("inventory").version(2).build());
        when(cardDao.softDelete("inventory", "mem-1", "旧阈值已被新规则推翻")).thenReturn(1);
        service.retire(new AgentMemoryLifecycleService.RetireCommand("inventory", "mem-1", "MESSAGE", "18", "阈值改为 2%", "旧阈值已被新规则推翻"));
        verify(outboxDao).insert(argThat(event -> "DELETE".equals(event.getEventType())));
    }

    private AgentMemoryLifecycleService.UpsertCommand command() {
        return new AgentMemoryLifecycleService.UpsertCommand("inventory", "BUSINESS_RULE", "inventory.stock-threshold", "库存阈值", "库存差异阈值", "阈值为 2%", 80, false, "MESSAGE", "18", "阈值为 2%", "用户确认新阈值");
    }
}
