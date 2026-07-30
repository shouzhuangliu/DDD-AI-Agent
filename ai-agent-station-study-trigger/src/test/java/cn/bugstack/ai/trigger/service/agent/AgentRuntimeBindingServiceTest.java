package cn.bugstack.ai.trigger.service.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeBindingServiceTest {

    private IAgentRepository agentRepository;
    private AgentWorkspaceService agentWorkspaceService;
    private SkillScannerService skillScannerService;
    private AgentRuntimeBindingService service;

    @BeforeEach
    void setUp() {
        agentRepository = mock(IAgentRepository.class);
        agentWorkspaceService = mock(AgentWorkspaceService.class);
        skillScannerService = mock(SkillScannerService.class);
        service = new AgentRuntimeBindingService();
        ReflectionTestUtils.setField(service, "agentRepository", agentRepository);
        ReflectionTestUtils.setField(service, "agentWorkspaceService", agentWorkspaceService);
        ReflectionTestUtils.setField(service, "skillScannerService", skillScannerService);
        ReflectionTestUtils.setField(service, "reActToolAllowlistPolicy", new ReActToolAllowlistPolicy());
    }

    @Test
    void assembleSyncsWorkspaceAndDerivesRuntimeCapabilities() {
        when(agentRepository.queryAgentById("cs")).thenReturn(AiAgentVO.builder()
                .agentId("cs")
                .agentName("CS Agent")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("cs")).thenReturn(List.of("enterprise-demo-skill-1.0.0"));
        when(agentRepository.queryBoundMcpIds("cs")).thenReturn(List.of("enterprise-demo-mcp"));
        when(agentRepository.queryBoundToolIds("cs")).thenReturn(List.of("task"));
        when(agentWorkspaceService.syncSkills("cs", "D:/repo", "D:/fallback", List.of("enterprise-demo-skill-1.0.0")))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/cs"));
        when(skillScannerService.readSkillMetadataFromWorkDir("D:\\repo\\.ma\\workspaces\\cs", "enterprise-demo-skill-1.0.0"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("enterprise-demo-skill-1.0.0")
                        .skillName("Enterprise Demo Skill")
                        .description("企业演示技能")
                        .build());
        when(agentRepository.queryMcpToolsByIds(List.of("enterprise-demo-mcp"))).thenReturn(List.of(
                AiClientToolMcpVO.builder()
                        .mcpId("enterprise-demo-mcp")
                        .mcpName("Enterprise Demo MCP")
                        .transportType("stdio")
                        .build()
        ));

        AgentRuntimeBindingService.AgentRuntimeBindings bindings = service.assemble("cs", "D:/fallback", true);

        assertEquals("cs", bindings.getAgent().getAgentId());
        assertEquals(Path.of("D:/repo/.ma/workspaces/cs"), bindings.getWorkspace());
        assertEquals(List.of("enterprise-demo-skill-1.0.0"), bindings.getSkillIds());
        assertEquals(List.of("enterprise-demo-mcp"), bindings.getMcpIds());
        assertEquals(List.of("task"), bindings.getExplicitToolIds());
        assertEquals(List.of("task", "read_file", "call_mcp_tool", "dispatch_subagents"), bindings.getEffectiveToolIds());
        assertTrue(bindings.getSkillMetadataById().containsKey("enterprise-demo-skill-1.0.0"));
        assertEquals(1, bindings.getMcpTools().size());
        verify(agentWorkspaceService).syncSkills("cs", "D:/repo", "D:/fallback", List.of("enterprise-demo-skill-1.0.0"));
    }

    @Test
    void assembleWithoutSyncUsesResolvedWorkspace() {
        when(agentRepository.queryAgentById("ops")).thenReturn(AiAgentVO.builder()
                .agentId("ops")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("ops")).thenReturn(List.of());
        when(agentRepository.queryBoundMcpIds("ops")).thenReturn(List.of());
        when(agentRepository.queryBoundToolIds("ops")).thenReturn(List.of("read_file", "unknown"));
        when(agentWorkspaceService.resolveWorkDir("ops", "D:/repo", "D:/fallback"))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/ops"));
        when(agentRepository.queryMcpToolsByIds(List.of())).thenReturn(List.of());

        AgentRuntimeBindingService.AgentRuntimeBindings bindings = service.assemble("ops", "D:/fallback", false);

        assertEquals(List.of("read_file"), bindings.getExplicitToolIds());
        assertEquals(List.of("read_file"), bindings.getEffectiveToolIds());
        verify(agentWorkspaceService).resolveWorkDir("ops", "D:/repo", "D:/fallback");
    }
}
