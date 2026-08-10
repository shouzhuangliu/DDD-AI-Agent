package cn.bugstack.ai.trigger.service.agent;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActExecuteStrategyPromptTest {

    @Test
    void promptExposesMcpServicesAndProgressiveDiscoveryInsteadOfToolCatalog() {
        AgentRuntimeBindingService.AgentRuntimeBindings bindings = AgentRuntimeBindingService.AgentRuntimeBindings.builder()
                .agent(AiAgentVO.builder().agentId("inventory-agent").systemPrompt("库存反馈助手").build())
                .skillIds(List.of())
                .mcpIds(List.of("inventory-feedback-mcp"))
                .skillMetadataById(Map.of())
                .mcpTools(List.of(AiClientToolMcpVO.builder()
                        .mcpId("inventory-feedback-mcp")
                        .mcpName("库存反馈 MCP")
                        .transportType("stdio")
                        .build()))
                .build();

        String prompt = ReflectionTestUtils.invokeMethod(
                new ReActExecuteStrategy(),
                "buildSystemPrompt",
                bindings,
                List.of("discover_mcp_tools", "call_mcp_tool"),
                List.of()
        );

        assertTrue(prompt.contains("inventory-feedback-mcp"));
        assertTrue(prompt.contains("discover_mcp_tools"));
        assertTrue(prompt.contains("inputSchema"));
        assertTrue(prompt.contains("toolHandle"));
        assertFalse(prompt.contains("get_today_feedback"));
        assertFalse(prompt.contains("get_mcp_tool_schema"));
        assertFalse(prompt.contains("tools/list"));
    }
}
