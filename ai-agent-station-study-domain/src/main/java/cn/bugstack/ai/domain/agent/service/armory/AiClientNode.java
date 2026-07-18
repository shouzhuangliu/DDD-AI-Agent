package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiModelBeanName;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport{
    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，客户端{}", JSON.toJSONString(requestParameter));
        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());
        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap
                = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());
        List<AiClientModelVO> enabledModels = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName());
        for(AiClientVO aiClientVO : aiClientList) {
            // 1. 预设话术
            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            List<String> promptIdList = aiClientVO.getPromptIdList();
            for (String promptId : promptIdList) {
                //获取绑定的提示词id内容 然后在组合提示词
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap.get(promptId);
                defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
            }
            // 2. 对话模型
            if (!applicationContext.containsBean(aiClientVO.getModelBeanName())) {
                log.warn("跳过默认模型不可用的客户端，clientId={}", aiClientVO.getClientId());
                continue;
            }
            OpenAiChatModel chatModel = getBean(aiClientVO.getModelBeanName());
            // 3. MCP 服务
            List<McpSyncClient> mcpSyncClients = new ArrayList<>();
            List<String> mcpBeanNameList = aiClientVO.getMcpBeanNameList();
            for (String mcpBeanName : mcpBeanNameList) {
                mcpSyncClients.add(getBean(mcpBeanName));
            }
            // 4. 顾问角色
            List<Advisor> advisors = new ArrayList<>();
            List<String> advisorBeanNameList = aiClientVO.getAdvisorBeanNameList();
            for (String advisorBeanName : advisorBeanNameList) {
                advisors.add(getBean(advisorBeanName));
            }

            Advisor[] advisorArray = advisors.toArray(new Advisor[]{});
            // 5. 构建对话客户端
            ChatClient chatClient = buildChatClient(chatModel, defaultSystem.toString(), mcpSyncClients, advisorArray);

            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, chatClient);

            if (enabledModels == null) {
                continue;
            }
            for (AiClientModelVO enabledModel : enabledModels) {
                String modelBeanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(enabledModel.getModelId());
                if (!applicationContext.containsBean(modelBeanName)) {
                    continue;
                }
                OpenAiChatModel variantModel = getBean(modelBeanName);
                ChatClient variantClient = buildChatClient(
                        variantModel, defaultSystem.toString(), mcpSyncClients, advisorArray);
                registerBean(AiModelBeanName.clientVariant(aiClientVO.getClientId(), enabledModel.getModelId()),
                        ChatClient.class, variantClient);
            }
        }
        return router(requestParameter, dynamicContext);
    }

    private ChatClient buildChatClient(OpenAiChatModel chatModel,
                                       String defaultSystem,
                                       List<McpSyncClient> mcpSyncClients,
                                       Advisor[] advisors) {
        return ChatClient.builder(chatModel)
                .defaultSystem(defaultSystem)
                .defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpSyncClients.toArray(new McpSyncClient[]{})))
                .defaultAdvisors(advisors)
                .build();
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }
}
