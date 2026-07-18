package cn.bugstack.ai.domain.agent.service.armory.business.data;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Client 装配数据加载策略。
 * <p>
 * RootNode 只负责按 commandType 路由策略，本类负责把创建 ChatClient 所需的
 * API、Model、MCP、Prompt、Advisor、Client 数据一次性放入 DynamicContext。
 */
@Component("aiClientLoadDataStrategy")
public class AiClientLoadDataStrategy implements ILoadDataStrategy {

    @Resource
    private IAgentRepository agentRepository;

    @Override
    public void loadData(ArmoryCommandEntity requestParameter,
                         DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {
        List<String> clientIds = safeIds(requestParameter.getCommandIdList());
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_API.getDataName(),
                agentRepository.queryAiClientApiVOListByClientIds(clientIds));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName(),
                agentRepository.AiClientToolMcpVOByClientIds(clientIds));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName(),
                mergeByModelId(agentRepository.AiClientModelVOByClientIds(clientIds),
                        agentRepository.queryEnabledModelVOList()));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName(),
                agentRepository.queryAiClientSystemPromptMapByClientIds(clientIds));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName(),
                agentRepository.AiClientAdvisorVOByClientIds(clientIds));
        dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT.getDataName(),
                agentRepository.AiClientVOByClientIds(clientIds));
    }

    private List<String> safeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<AiClientModelVO> mergeByModelId(List<AiClientModelVO> primary,
                                                 List<AiClientModelVO> secondary) {
        Map<String, AiClientModelVO> modelMap = new LinkedHashMap<>();
        addModels(modelMap, primary);
        addModels(modelMap, secondary);
        return new ArrayList<>(modelMap.values());
    }

    private void addModels(Map<String, AiClientModelVO> modelMap, List<AiClientModelVO> models) {
        if (models == null || models.isEmpty()) return;
        for (AiClientModelVO model : models) {
            if (model == null || model.getModelId() == null) continue;
            modelMap.putIfAbsent(model.getModelId(), model);
        }
    }
}
