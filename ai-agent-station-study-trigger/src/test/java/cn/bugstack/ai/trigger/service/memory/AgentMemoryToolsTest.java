package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.memory.GetAgentMemoryTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.SearchAgentMemoryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentMemoryToolsTest {

    @AfterEach
    void clear() { ReActToolContextHolder.clear(); }

    @Test
    void searchToolReturnsIndexWithoutFullContentAndUsesContextAgent() {
        AgentMemoryCatalogPort catalog = mock(AgentMemoryCatalogPort.class);
        when(catalog.search("inventory", "库存不一致", 5)).thenReturn(List.of(
                new AgentMemoryCatalogPort.MemoryIndexItem("inventory", "mem-1", 1,
                        "RESOLVED_CASE", "库存不一致", "下单后库存未扣减", "case-1", 0.9)));
        ReActToolContextHolder.set(ReActToolContext.builder().agentId("inventory").build());

        String result = new SearchAgentMemoryTool(catalog).search("库存不一致", 20);

        assertTrue(result.contains("mem-1"));
        assertFalse(result.contains("contentJson"));
        verify(catalog).search("inventory", "库存不一致", 5);
    }

    @Test
    void getToolReadsAtMostThreePublishedMemoriesForContextAgent() {
        AgentMemoryCatalogPort catalog = mock(AgentMemoryCatalogPort.class);
        ReActToolContextHolder.set(ReActToolContext.builder().agentId("inventory").build());
        GetAgentMemoryTool tool = new GetAgentMemoryTool(catalog);

        tool.get("[\"m1\",\"m2\",\"m3\",\"m4\"]");

        verify(catalog).getPublished("inventory", List.of("m1", "m2", "m3"));
    }
}
