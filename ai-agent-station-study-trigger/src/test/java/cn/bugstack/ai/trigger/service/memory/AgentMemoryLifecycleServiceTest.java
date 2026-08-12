package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryChangeLogDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryIndexOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(cardDao.softDelete("inventory", "mem-1", "replaced by a new rule")).thenReturn(1);

        service.retire(new AgentMemoryLifecyclePort.RetireCommand(
                "inventory", "mem-1", "MESSAGE", "18", "threshold changed", "replaced by a new rule"));

        verify(outboxDao).insert(argThat(event -> "DELETE".equals(event.getEventType())));
    }

    private AgentMemoryLifecyclePort.UpsertCommand command() {
        return new AgentMemoryLifecyclePort.UpsertCommand("inventory", "BUSINESS_RULE", "inventory.stock-threshold",
                "inventory threshold", "inventory variance threshold", "{\"threshold\":2}",
                80, false, "MESSAGE", "18", "threshold is 2 percent", "confirmed new threshold");
    }
}
