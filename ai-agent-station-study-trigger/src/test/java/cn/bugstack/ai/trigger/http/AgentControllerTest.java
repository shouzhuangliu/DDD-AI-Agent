package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
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
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private IAiAgentDao aiAgentDao;
    private IAgentRepository agentRepository;
    private SkillScannerService skillScannerService;
    private AgentWorkspaceService agentWorkspaceService;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        aiAgentDao = mock(IAiAgentDao.class);
        agentRepository = mock(IAgentRepository.class);
        skillScannerService = mock(SkillScannerService.class);
        agentWorkspaceService = mock(AgentWorkspaceService.class);
        controller = new AgentController();
        ReflectionTestUtils.setField(controller, "aiAgentDao", aiAgentDao);
        ReflectionTestUtils.setField(controller, "agentRepository", agentRepository);
        ReflectionTestUtils.setField(controller, "skillScannerService", skillScannerService);
        ReflectionTestUtils.setField(controller, "agentWorkspaceService", agentWorkspaceService);
        ReflectionTestUtils.setField(controller, "reActToolAllowlistPolicy", new ReActToolAllowlistPolicy());
        ReActToolProperties properties = new ReActToolProperties();
        properties.setWorkDir("D:/repo");
        ReflectionTestUtils.setField(controller, "properties", properties);
    }

    @Test
    void returnsOnlyBoundRuntimeCapabilities() {
        when(aiAgentDao.queryByAgentId("cs")).thenReturn(AiAgent.builder()
                .agentId("cs")
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
