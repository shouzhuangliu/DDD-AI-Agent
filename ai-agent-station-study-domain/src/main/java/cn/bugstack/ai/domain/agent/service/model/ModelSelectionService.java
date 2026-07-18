package cn.bugstack.ai.domain.agent.service.model;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class ModelSelectionService {

    public static final String DEFAULT_MODEL_ID = "2001";

    private final ApplicationContext applicationContext;

    public ModelSelectionService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void requireAvailable(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        String beanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId.trim());
        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalArgumentException("模型不存在、已禁用或密钥未配置");
        }
    }

    public static String select(String requestedModelId, String agentModelId) {
        if (requestedModelId != null && !requestedModelId.isBlank()) {
            return requestedModelId.trim();
        }
        if (agentModelId != null && !agentModelId.isBlank()) {
            return agentModelId.trim();
        }
        return DEFAULT_MODEL_ID;
    }
}
