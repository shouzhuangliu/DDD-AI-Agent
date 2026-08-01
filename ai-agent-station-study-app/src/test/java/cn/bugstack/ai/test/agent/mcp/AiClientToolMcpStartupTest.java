package cn.bugstack.ai.test.agent.mcp;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.armory.AiClientToolMcpNode;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiClientToolMcpStartupTest {

    @Test
    void registersEnabledMcpsWhenApplicationIsReady() {
        IAgentRepository repository = mock(IAgentRepository.class);
        AiClientToolMcpNode node = spy(new AiClientToolMcpNode());
        ReflectionTestUtils.setField(node, "repository", repository);
        doNothing().when(node).registerMcpSyncClient(
                "inventory-feedback-mcp", "库存反馈 MCP", "stdio", "{}", 60);
        when(repository.queryEnabledMcpTools()).thenReturn(List.of(AiClientToolMcpVO.builder()
                .mcpId("inventory-feedback-mcp")
                .mcpName("库存反馈 MCP")
                .transportType("stdio")
                .transportConfig("{}")
                .requestTimeout(60)
                .build()));

        node.registerEnabledMcpsAtStartup();

        verify(node).registerMcpSyncClient(
                "inventory-feedback-mcp", "库存反馈 MCP", "stdio", "{}", 60);
    }
}
