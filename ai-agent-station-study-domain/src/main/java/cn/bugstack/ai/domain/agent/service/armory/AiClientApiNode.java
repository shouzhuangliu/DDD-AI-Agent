package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport {
    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;
    @Resource
    private ModelCredentialResolver modelCredentialResolver;
    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建，API 构建节点 {}", JSON.toJSONString(requestParameter));
        List<AiClientApiVO> aiClientApiVoList = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_API.getDataName());
        if(aiClientApiVoList == null || aiClientApiVoList.isEmpty()) {
            return null;
        }
        for(AiClientApiVO aiClientApiVO : aiClientApiVoList) {
            String apiKey = modelCredentialResolver.resolve(aiClientApiVO.getApiKey());
            if (apiKey == null) {
                log.warn("跳过未配置凭据的模型 API，apiId={}", aiClientApiVO.getApiId());
                continue;
            }
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(aiClientApiVO.getBaseUrl())
                    .apiKey(apiKey)
                    .completionsPath(aiClientApiVO.getCompletionsPath())
                    .embeddingsPath(aiClientApiVO.getEmbeddingsPath())
                    .build();
            // 注册Bean
            registerBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(aiClientApiVO.getApiId()),
                    OpenAiApi.class ,
                    openAiApi);
        }
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientToolMcpNode;
    }
}
