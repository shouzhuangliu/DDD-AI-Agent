package cn.bugstack.ai.domain.agent.service.armory.business.data;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Model 维度装配数据加载策略。
 */
@Component("aiClientModelLoadDataStrategy")
public class AiClientModelLoadDataStrategy implements ILoadDataStrategy {

    @Resource
    private IAgentRepository agentRepository;

    @Override
    public void loadData(ArmoryCommandEntity requestParameter,
                         DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {
        List<String> modelIds = safeIds(requestParameter.getCommandIdList());
        List<AiClientModelVO> models = agentRepository.AiClientModelVOByModelIds(modelIds);
        List<String> mcpIds = models.stream()
                .flatMap(model -> model.getToolMcpIds().stream())
                .distinct()
                .toList();
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_API.getDataName(),
                agentRepository.queryAiClientApiVOListByModelIds(modelIds));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName(),
                agentRepository.queryMcpToolsByIds(mcpIds));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName(), models);
    }

    private List<String> safeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
