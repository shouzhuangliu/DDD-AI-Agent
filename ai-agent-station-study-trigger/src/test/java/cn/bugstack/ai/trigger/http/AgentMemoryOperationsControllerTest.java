package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryChangeLogDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryChangeLog;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentMemoryOperationsControllerTest {

    @Test
    void retireEndpointUsesAutomaticLifecycleAndReturnsSoftDeletedStatus() throws Exception {
        AgentMemoryLifecyclePort lifecycle = mock(AgentMemoryLifecyclePort.class);
        when(lifecycle.retire(any())).thenReturn(new AgentMemoryLifecyclePort.Result("mem-1", 2, "RETIRE"));
        MockMvc mvc = mvc(lifecycle, mock(AgentMemoryCatalogPort.class), mock(IAgentMemoryChangeLogDao.class));

        mvc.perform(post("/api/v1/agents/inventory/memory/memories/mem-1/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"MESSAGE\",\"sourceId\":\"18\",\"evidenceQuote\":\"new threshold\",\"reason\":\"superseded\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETIRED"));
        verify(lifecycle).retire(any());
    }

    @Test
    void searchReturnsOnlyLightweightIndex() throws Exception {
        AgentMemoryCatalogPort catalog = mock(AgentMemoryCatalogPort.class);
        when(catalog.search(eq("inventory"), eq("库存"), eq(5))).thenReturn(List.of(
                new AgentMemoryCatalogPort.MemoryIndexItem("inventory", "mem-1", 1,
                        "BUSINESS_RULE", "库存规则", "库存差异处理", "case-1", 0.9)));
        MockMvc mvc = mvc(mock(AgentMemoryLifecyclePort.class), catalog, mock(IAgentMemoryChangeLogDao.class));

        mvc.perform(get("/api/v1/agents/inventory/memory/memories/search").param("query", "库存"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memoryId").value("mem-1"))
                .andExpect(jsonPath("$[0].contentJson").doesNotExist());
    }

    @Test
    void auditEndpointReadsChangeLogForCurrentAgentMemory() throws Exception {
        IAgentMemoryChangeLogDao audit = mock(IAgentMemoryChangeLogDao.class);
        when(audit.queryByMemoryId("inventory", "mem-1", 20)).thenReturn(List.of(
                AgentMemoryChangeLog.builder().memoryId("mem-1").operation("UPDATE").build()));
        MockMvc mvc = mvc(mock(AgentMemoryLifecyclePort.class), mock(AgentMemoryCatalogPort.class), audit);

        mvc.perform(get("/api/v1/agents/inventory/memory/memories/mem-1/audit"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].operation").value("UPDATE"));
    }

    private MockMvc mvc(AgentMemoryLifecyclePort lifecycle, AgentMemoryCatalogPort catalog, IAgentMemoryChangeLogDao audit) {
        return MockMvcBuilders.standaloneSetup(new AgentMemoryOperationsController(lifecycle, catalog, audit)).build();
    }
}
