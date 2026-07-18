package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.*;

import java.util.List;
import java.util.Map;

/**
 * AiAgent 仓储接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/27 16:48
 */
public interface IAgentRepository {

    List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList);

    List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList);

    List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList);

    List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList);

    Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList);

    List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList);

    List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList);

    List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList);

    List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList);

    List<AiClientModelVO> queryEnabledModelVOList();

    Map<String,AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId);

    // ========== 专属 Agent 系统 ==========

    /**
     * 按 agentId 查 Agent 主体（含 soul/modelId/workDir/channel）
     */
    AiAgentVO queryAgentById(String agentId);

    /**
     * 查 Agent 绑定的 skill id 列表
     */
    List<String> queryBoundSkillIds(String agentId);

    /**
     * 查 Agent 绑定的 mcp id 列表
     */
    List<String> queryBoundMcpIds(String agentId);

    /**
     * 按 mcpId 列表查询 MCP 工具信息（用于给 LLM 展示可用 MCP 列表）
     */
    List<cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO> queryMcpToolsByIds(List<String> mcpIds);

    /**
     * 全量覆盖式重绑 skills：先删后插
     */
    int bindSkills(String agentId, List<String> skillIds);

    /**
     * 全量覆盖式重绑 mcps：先删后插
     */
    int bindMcps(String agentId, List<String> mcpIds);
}
