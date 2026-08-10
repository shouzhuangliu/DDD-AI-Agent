package cn.bugstack.ai.domain.agent.service.runtime;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import jakarta.annotation.Resource;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class AgentRuntimeBindingService {

    @Resource
    private IAgentRepository agentRepository;

    @Resource
    private AgentWorkspaceService agentWorkspaceService;

    @Resource
    private SkillScannerService skillScannerService;

    @Resource
    private ReActToolAllowlistPolicy reActToolAllowlistPolicy;

    public AgentRuntimeBindings assemble(String agentId, String fallbackBaseDir, boolean syncWorkspace) {
        AiAgentVO agent = agentRepository.queryAgentById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        List<String> skillIds = agentRepository.queryBoundSkillIds(agentId);
        List<String> mcpIds = agentRepository.queryBoundMcpIds(agentId);
        List<String> explicitToolIds = reActToolAllowlistPolicy.resolve(agentRepository.queryBoundToolIds(agentId));
        Path workspace = syncWorkspace
                ? agentWorkspaceService.syncSkills(agentId, agent.getWorkDir(), fallbackBaseDir, skillIds)
                : agentWorkspaceService.resolveWorkDir(agentId, agent.getWorkDir(), fallbackBaseDir);
        List<String> effectiveToolIds = resolveEffectiveToolIds(explicitToolIds, skillIds, mcpIds);
        Map<String, SkillScannerService.SkillInfo> skillMetadata = new LinkedHashMap<>();
        for (String skillId : skillIds) {
            SkillScannerService.SkillInfo metadata = skillScannerService.readSkillMetadataFromWorkDir(workspace.toString(), skillId);
            if (metadata != null) {
                skillMetadata.put(skillId, metadata);
            }
        }
        List<AiClientToolMcpVO> mcpTools = agentRepository.queryMcpToolsByIds(mcpIds);
        return AgentRuntimeBindings.builder()
                .agent(agent)
                .workspace(workspace)
                .skillIds(skillIds)
                .mcpIds(mcpIds)
                .explicitToolIds(explicitToolIds)
                .effectiveToolIds(effectiveToolIds)
                .skillMetadataById(skillMetadata)
                .mcpTools(mcpTools)
                .build();
    }

    public List<String> resolveEffectiveToolIds(List<String> explicitToolIds, List<String> skillIds, List<String> mcpIds) {
        LinkedHashSet<String> tools = new LinkedHashSet<>(explicitToolIds == null ? List.of() : explicitToolIds);
        if (skillIds != null && !skillIds.isEmpty()) {
            tools.add(ReActToolAllowlistPolicy.READ_FILE);
        }
        if (mcpIds != null && !mcpIds.isEmpty()) {
            tools.add(ReActToolAllowlistPolicy.DISCOVER_MCP_TOOLS);
            tools.add(ReActToolAllowlistPolicy.CALL_MCP_TOOL);
        } else {
            tools.remove(ReActToolAllowlistPolicy.DISCOVER_MCP_TOOLS);
            tools.remove(ReActToolAllowlistPolicy.GET_MCP_TOOL_SCHEMA);
            tools.remove(ReActToolAllowlistPolicy.CALL_MCP_TOOL);
        }
        if (tools.contains(ReActToolAllowlistPolicy.TASK)) {
            tools.add(ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS);
        }
        return List.copyOf(tools);
    }

    @Value
    @Builder
    public static class AgentRuntimeBindings {
        AiAgentVO agent;
        Path workspace;
        List<String> skillIds;
        List<String> mcpIds;
        List<String> explicitToolIds;
        List<String> effectiveToolIds;
        Map<String, SkillScannerService.SkillInfo> skillMetadataById;
        List<AiClientToolMcpVO> mcpTools;
    }
}
