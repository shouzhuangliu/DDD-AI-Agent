package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.armory.AiClientToolMcpNode;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import cn.bugstack.ai.trigger.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private IAiAgentDao aiAgentDao;
    private IAgentRepository agentRepository;
    private SkillScannerService skillScannerService;
    private AgentWorkspaceService agentWorkspaceService;
    private CapabilityRegistryService capabilityRegistryService;
    private AgentRuntimeBindingService agentRuntimeBindingService;
    private AiClientToolMcpNode aiClientToolMcpNode;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        aiAgentDao = mock(IAiAgentDao.class);
        agentRepository = mock(IAgentRepository.class);
        skillScannerService = mock(SkillScannerService.class);
        agentWorkspaceService = mock(AgentWorkspaceService.class);
        capabilityRegistryService = mock(CapabilityRegistryService.class);
        aiClientToolMcpNode = mock(AiClientToolMcpNode.class);
        agentRuntimeBindingService = new AgentRuntimeBindingService();
        ReflectionTestUtils.setField(agentRuntimeBindingService, "agentRepository", agentRepository);
        ReflectionTestUtils.setField(agentRuntimeBindingService, "agentWorkspaceService", agentWorkspaceService);
        ReflectionTestUtils.setField(agentRuntimeBindingService, "skillScannerService", skillScannerService);
        ReflectionTestUtils.setField(agentRuntimeBindingService, "reActToolAllowlistPolicy", new ReActToolAllowlistPolicy());
        controller = new AgentController();
        ReflectionTestUtils.setField(controller, "aiAgentDao", aiAgentDao);
        ReflectionTestUtils.setField(controller, "agentRepository", agentRepository);
        ReflectionTestUtils.setField(controller, "skillScannerService", skillScannerService);
        ReflectionTestUtils.setField(controller, "agentWorkspaceService", agentWorkspaceService);
        ReflectionTestUtils.setField(controller, "capabilityRegistryService", capabilityRegistryService);
        ReflectionTestUtils.setField(controller, "aiClientToolMcpNode", aiClientToolMcpNode);
        ReflectionTestUtils.setField(controller, "reActToolAllowlistPolicy", new ReActToolAllowlistPolicy());
        ReflectionTestUtils.setField(controller, "agentRuntimeBindingService", agentRuntimeBindingService);
        ReActToolProperties properties = new ReActToolProperties();
        properties.setWorkDir("D:/repo");
        ReflectionTestUtils.setField(controller, "properties", properties);
    }

    @Test
    void registersBoundMcpClientImmediately() {
        when(aiAgentDao.queryByAgentId("inventory")).thenReturn(AiAgent.builder()
                .agentId("inventory")
                .workDir("D:/repo")
                .build());
        when(agentWorkspaceService.syncSkills("inventory", "D:/repo", "D:/repo", List.of()))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/inventory"));
        AiClientToolMcpVO mcp = AiClientToolMcpVO.builder()
                .mcpId("inventory-feedback-mcp")
                .mcpName("库存反馈 MCP")
                .transportType("stdio")
                .transportConfig("{\"command\":\"python\",\"args\":[\"mcp-test-server/inventory_feedback_mcp.py\"]}")
                .requestTimeout(60)
                .build();
        when(agentRepository.queryMcpToolsByIds(List.of("inventory-feedback-mcp")))
                .thenReturn(List.of(mcp));

        Map<String, Object> result = controller.updateBindings("inventory", Map.of(
                "skillIds", List.of(),
                "mcpIds", List.of("inventory-feedback-mcp"),
                "toolIds", List.of("call_mcp_tool")
        ));

        assertEquals(true, result.get("success"));
        verify(aiClientToolMcpNode).registerMcpSyncClient(
                "inventory-feedback-mcp", "库存反馈 MCP", "stdio", mcp.getTransportConfig(), 60);
    }

    @Test
    void returnsOnlyBoundRuntimeCapabilities() {
        when(aiAgentDao.queryByAgentId("cs")).thenReturn(AiAgent.builder()
                .agentId("cs")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryAgentById("cs")).thenReturn(cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO.builder()
                .agentId("cs")
                .agentName("CS Agent")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("cs")).thenReturn(List.of("enterprise-demo-skill-1.0.0"));
        when(agentRepository.queryBoundMcpIds("cs")).thenReturn(List.of("enterprise-demo-mcp"));
        when(agentRepository.queryBoundToolIds("cs")).thenReturn(List.of("read_file"));
        when(agentWorkspaceService.resolveWorkDir("cs", "D:/repo", "D:/repo"))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/cs"));
        when(skillScannerService.readSkillMetadataFromWorkDir("D:\\repo\\.ma\\workspaces\\cs", "enterprise-demo-skill-1.0.0"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("enterprise-demo-skill-1.0.0")
                        .skillName("Enterprise Demo Skill")
                        .description("企业演示技能")
                        .content("")
                        .build());
        when(agentRepository.queryMcpToolsByIds(List.of("enterprise-demo-mcp"))).thenReturn(List.of(
                AiClientToolMcpVO.builder()
                        .mcpId("enterprise-demo-mcp")
                        .mcpName("Enterprise Demo MCP")
                        .transportType("stdio")
                        .build()
        ));

        Map<String, Object> result = controller.getBindingDetails("cs");

        assertEquals(true, result.get("success"));
        List<Map<String, Object>> skills = cast(result.get("skills"));
        List<Map<String, Object>> tools = cast(result.get("tools"));
        List<Map<String, Object>> mcps = cast(result.get("mcps"));
        List<Map<String, Object>> effectiveTools = cast(result.get("effectiveTools"));
        assertEquals(1, skills.size());
        assertEquals("enterprise-demo-skill-1.0.0", skills.getFirst().get("skillId"));
        assertEquals(".ma/skills/enterprise-demo-skill-1.0.0/SKILL.md", skills.getFirst().get("runtimePath"));
        assertEquals(true, skills.getFirst().get("runtimeAvailable"));
        assertEquals(1, tools.size());
        assertEquals("read_file", tools.getFirst().get("toolId"));
        assertEquals(1, mcps.size());
        assertEquals("enterprise-demo-mcp", mcps.getFirst().get("mcpId"));
        assertEquals(true, mcps.getFirst().get("runtimeAvailable"));
        assertEquals(2, effectiveTools.size());
        assertEquals("read_file", effectiveTools.get(0).get("toolId"));
        assertEquals("agent_binding", effectiveTools.get(0).get("source"));
        assertEquals("call_mcp_tool", effectiveTools.get(1).get("toolId"));
        assertEquals("mcp_binding", effectiveTools.get(1).get("source"));
        assertTrue(result.get("workspace").toString().contains("workspaces"));
    }

    @Test
    void marksMissingRuntimeBindingsAsUnavailable() {
        when(aiAgentDao.queryByAgentId("ops")).thenReturn(AiAgent.builder()
                .agentId("ops")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryAgentById("ops")).thenReturn(cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO.builder()
                .agentId("ops")
                .agentName("OPS Agent")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("ops")).thenReturn(List.of("missing-skill"));
        when(agentRepository.queryBoundMcpIds("ops")).thenReturn(List.of("missing-mcp"));
        when(agentRepository.queryBoundToolIds("ops")).thenReturn(List.of());
        when(agentWorkspaceService.resolveWorkDir("ops", "D:/repo", "D:/repo"))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/ops"));
        when(agentRepository.queryMcpToolsByIds(List.of("missing-mcp"))).thenReturn(List.of());

        Map<String, Object> result = controller.getBindingDetails("ops");

        List<Map<String, Object>> skills = cast(result.get("skills"));
        List<Map<String, Object>> mcps = cast(result.get("mcps"));
        assertEquals(1, skills.size());
        assertEquals("missing-skill", skills.getFirst().get("skillId"));
        assertFalse((Boolean) skills.getFirst().get("runtimeAvailable"));
        assertEquals("UNAVAILABLE", skills.getFirst().get("runtimeStatus"));
        assertEquals(1, mcps.size());
        assertEquals("missing-mcp", mcps.getFirst().get("mcpId"));
        assertFalse((Boolean) mcps.getFirst().get("runtimeAvailable"));
        assertEquals("UNAVAILABLE", mcps.getFirst().get("runtimeStatus"));
    }

    @Test
    void readsSkillFromAgentRuntimeWorkspaceWhenAgentIdProvided() {
        when(aiAgentDao.queryByAgentId("cs")).thenReturn(AiAgent.builder()
                .agentId("cs")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("cs")).thenReturn(List.of("enterprise-demo-skill-1.0.0"));
        when(agentWorkspaceService.resolveWorkDir("cs", "D:/repo", "D:/repo"))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/cs"));
        when(skillScannerService.readSkillFromWorkDir("D:\\repo\\.ma\\workspaces\\cs", "enterprise-demo-skill-1.0.0"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("enterprise-demo-skill-1.0.0")
                        .skillName("Enterprise Runtime Skill")
                        .description("运行时技能")
                        .content("# runtime")
                        .build());

        Map<String, Object> result = controller.getSkill("enterprise-demo-skill-1.0.0", "cs");

        assertEquals(true, result.get("success"));
        SkillScannerService.SkillInfo skill = (SkillScannerService.SkillInfo) result.get("skill");
        assertEquals("Enterprise Runtime Skill", skill.getSkillName());
    }

    @Test
    void fallsBackToGlobalSkillWhenBoundSkillNotYetSyncedIntoRuntimeWorkspace() {
        when(aiAgentDao.queryByAgentId("inventory")).thenReturn(AiAgent.builder()
                .agentId("inventory")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("inventory")).thenReturn(List.of("inventory-feedback-agent"));
        when(agentWorkspaceService.resolveWorkDir("inventory", "D:/repo", "D:/repo"))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/inventory"));
        when(skillScannerService.readSkillFromWorkDir("D:\\repo\\.ma\\workspaces\\inventory", "inventory-feedback-agent"))
                .thenReturn(null);
        when(skillScannerService.readSkillFromWorkDir("D:/repo", "inventory-feedback-agent"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("inventory-feedback-agent")
                        .skillName("Inventory Feedback Agent")
                        .description("库存反馈技能")
                        .content("# skill")
                        .build());

        Map<String, Object> result = controller.getSkill("inventory-feedback-agent", "inventory");

        assertEquals(true, result.get("success"));
        SkillScannerService.SkillInfo skill = (SkillScannerService.SkillInfo) result.get("skill");
        assertEquals("Inventory Feedback Agent", skill.getSkillName());
    }

    @Test
    void doesNotFallbackToGlobalSkillWhenAgentHasNotBoundIt() {
        when(aiAgentDao.queryByAgentId("cs")).thenReturn(AiAgent.builder()
                .agentId("cs")
                .workDir("D:/repo")
                .build());
        when(agentRepository.queryBoundSkillIds("cs")).thenReturn(List.of("bound-skill"));
        when(agentWorkspaceService.resolveWorkDir("cs", "D:/repo", "D:/repo"))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/cs"));
        when(skillScannerService.readSkillFromWorkDir("D:/repo", "unbound-skill"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("unbound-skill")
                        .skillName("Global Skill")
                        .content("# global")
                        .build());

        Map<String, Object> result = controller.getSkill("unbound-skill", "cs");

        assertEquals(false, result.get("success"));
        assertEquals("Skill not bound to Agent", result.get("message"));
    }

    @Test
    void updateBindingsSyncsBoundSkillsToRuntimeWorkspace() {
        when(aiAgentDao.queryByAgentId("cs")).thenReturn(AiAgent.builder()
                .agentId("cs")
                .workDir("D:/repo")
                .build());
        when(agentWorkspaceService.syncSkills("cs", "D:/repo", "D:/repo", List.of("enterprise-demo-skill-1.0.0")))
                .thenReturn(Path.of("D:/repo/.ma/workspaces/cs"));

        Map<String, Object> result = controller.updateBindings("cs", Map.of(
                "skillIds", List.of("enterprise-demo-skill-1.0.0"),
                "mcpIds", List.of(),
                "toolIds", List.of("read_file")
        ));

        assertEquals(true, result.get("success"));
        assertEquals(Path.of("D:/repo/.ma/workspaces/cs").toString(), result.get("workspace"));
        verify(capabilityRegistryService).requireReleasedRuntimeBindings(List.of("enterprise-demo-skill-1.0.0"), List.of());
        verify(agentRepository).bindSkills("cs", List.of("enterprise-demo-skill-1.0.0"));
        verify(agentRepository).bindMcps("cs", List.of());
        verify(agentRepository).bindTools("cs", List.of("read_file"));
        verify(agentWorkspaceService).syncSkills("cs", "D:/repo", "D:/repo", List.of("enterprise-demo-skill-1.0.0"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
