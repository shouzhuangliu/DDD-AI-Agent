package cn.bugstack.ai.domain.agent.model.valobj;

public final class AiModelBeanName {

    private AiModelBeanName() {
    }

    public static String clientVariant(String clientId, String modelId) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(clientId) + "_model_" + modelId;
    }
}
