package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryCatalogServiceTest {

    @AfterEach
    void clear() { }

    @Test
    void searchReturnsAtMostFivePublishedIndexesForCurrentAgent() {
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        LongTermMemoryPort indexPort = mock(LongTermMemoryPort.class);
        when(indexPort.searchIndex(eq("inventory"), anyString(), anyInt())).thenReturn(List.of(
                new LongTermMemoryPort.MemoryIndexReference("inventory", "mem-1", 1, 0.91)));
        when(cardDao.queryPublishedByMemoryIds(eq("inventory"), anyList())).thenReturn(List.of(card("inventory", "mem-1")));
        when(cardDao.searchPublishedIndex(eq("inventory"), anyString(), anyInt())).thenReturn(List.of());

        AgentMemoryCatalogService service = new AgentMemoryCatalogService(cardDao, indexPort);
        List<AgentMemoryCatalogPort.MemoryIndexItem> result = service.search("inventory", "下单后库存不一致", 20);

        assertTrue(result.size() <= 5);
        assertTrue(result.stream().allMatch(item -> item.agentId().equals("inventory")));
        assertEquals("mem-1", result.getFirst().memoryId());
    }

    @Test
    void getRejectsMemoryOwnedByAnotherAgentAndLimitsContentToThree() {
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        LongTermMemoryPort indexPort = mock(LongTermMemoryPort.class);
        when(cardDao.queryPublishedByMemoryIds(eq("inventory"), anyList()))
                .thenReturn(List.of(card("ops", "ops-memory-1"), card("inventory", "mem-1")));
        AgentMemoryCatalogService service = new AgentMemoryCatalogService(cardDao, indexPort);

        List<AgentMemoryCatalogPort.MemoryContent> result = service.getPublished(
                "inventory", List.of("ops-memory-1", "mem-1", "mem-2", "mem-3"));

        assertEquals(1, result.size());
        assertEquals("mem-1", result.getFirst().memoryId());
    }

    @Test
    void getDoesNotReturnSoftDeletedMemoryEvenWhenIndexStillReferencesIt() {
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        LongTermMemoryPort indexPort = mock(LongTermMemoryPort.class);
        AgentMemoryCard deleted = card("inventory", "mem-deleted");
        deleted.setIsDeleted(1);
        when(cardDao.queryPublishedByMemoryIds(eq("inventory"), anyList())).thenReturn(List.of(deleted));

        List<AgentMemoryCatalogPort.MemoryContent> result = new AgentMemoryCatalogService(cardDao, indexPort)
                .getPublished("inventory", List.of("mem-deleted"));

        assertTrue(result.isEmpty());
    }

    private AgentMemoryCard card(String agentId, String memoryId) {
        return AgentMemoryCard.builder().agentId(agentId).memoryId(memoryId).version(1)
                .memoryType("RESOLVED_CASE").title("库存不一致").description("下单后库存未扣减")
                .contentJson("{\"resolution\":\"重建库存流水\"}").status("PUBLISHED")
                .sourceCaseId("case-1").build();
    }
}
