package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.trigger.service.memory.AgentMemoryCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentMemoryOperationsControllerTest {

    @Test
    void approvesCandidateWithReviewerAndReason() throws Exception {
        AgentMemoryCandidateService candidates = mock(AgentMemoryCandidateService.class);
        AgentMemoryCatalogPort catalog = mock(AgentMemoryCatalogPort.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentMemoryOperationsController(candidates, catalog)).build();

        mvc.perform(post("/api/v1/agents/inventory/memory/candidates/candidate-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"developer\",\"reason\":\"规则已确认\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        verify(candidates).approve("inventory", "candidate-1", "developer", "规则已确认");
    }

    @Test
    void searchReturnsOnlyLightweightIndex() throws Exception {
        AgentMemoryCandidateService candidates = mock(AgentMemoryCandidateService.class);
        AgentMemoryCatalogPort catalog = mock(AgentMemoryCatalogPort.class);
        when(catalog.search("inventory", "库存不一致", 5)).thenReturn(List.of(
                new AgentMemoryCatalogPort.MemoryIndexItem("inventory", "mem-1", 1,
                        "RESOLVED_CASE", "库存不一致", "下单后库存未扣减", "case-1", 0.9)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentMemoryOperationsController(candidates, catalog)).build();

        mvc.perform(get("/api/v1/agents/inventory/memory/memories/search").param("query", "库存不一致"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memoryId").value("mem-1"))
                .andExpect(jsonPath("$[0].contentJson").doesNotExist());
    }

    @Test
    void rejectsWriteWithoutAuditReason() {
        AgentMemoryCandidateService candidates = mock(AgentMemoryCandidateService.class);
        AgentMemoryCatalogPort catalog = mock(AgentMemoryCatalogPort.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentMemoryOperationsController(candidates, catalog)).build();

        assertThrows(Exception.class, () -> mvc.perform(
                post("/api/v1/agents/inventory/memory/candidates/candidate-1/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"developer\"}")));
        verifyNoInteractions(candidates);
    }
}
