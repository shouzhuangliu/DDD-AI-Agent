package cn.bugstack.ai.trigger.service.model;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.armory.ModelCredentialResolver;
import cn.bugstack.ai.domain.agent.service.model.ModelRetryPolicy;
import cn.bugstack.ai.infrastructure.dao.IAiClientApiDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientModelDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientApi;
import cn.bugstack.ai.infrastructure.dao.po.AiClientModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 大模型 Bean 统一装配器。
 * <p>
 * 项目里存在两条模型 Bean 注册路径：启动时 armory 树（{@code AiClientApiNode} + {@code AiClientModelNode}）
 * 与运行时 HTTP 保存（{@code AgentController#refreshRuntimeModel}）。两者构建 {@link OpenAiChatModel} 的方式
 * 不一致（armory 会叠加 MCP toolCallbacks，HTTP 保存不会），且删除模型时不会注销 Bean。
 * <p>
 * 本类把“给定 api + model → 注册 / 注销 Spring Bean”收敛为唯一入口，供启动兜底 Runner 与 AgentController
 * 的增删改查复用。这里注册的是<strong>纯模型</strong>（不带 toolCallbacks），执行策略（Chat / ReAct）会各自
 * 用 {@link org.springframework.ai.chat.client.ChatClient} 重新组装工具链，因此模型本身保持轻量。
 * <p>
 * 注册的 Bean 名称遵循 {@link AiAgentEnumVO#AI_CLIENT_MODEL} / {@link AiAgentEnumVO#AI_CLIENT_API} 的命名约定：
 * <ul>
 *   <li>{@code ai_client_api_<apiId>} —— {@link OpenAiApi}</li>
 *   <li>{@code ai_client_model_<modelId>} —— {@link OpenAiChatModel}</li>
 * </ul>
 * 注册逻辑与 {@code AbstractArmorySupport#registerBean} 一致：DefaultListableBeanFactory + 单例，已存在先移除。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Service
public class ChatModelBeanRegistrar {

    private final IAiClientApiDao aiClientApiDao;
    private final IAiClientModelDao aiClientModelDao;
    private final ApplicationContext applicationContext;
    private final ModelCredentialResolver modelCredentialResolver;

    public ChatModelBeanRegistrar(IAiClientApiDao aiClientApiDao,
                                  IAiClientModelDao aiClientModelDao,
                                  ApplicationContext applicationContext,
                                  ModelCredentialResolver modelCredentialResolver) {
        this.aiClientApiDao = aiClientApiDao;
        this.aiClientModelDao = aiClientModelDao;
        this.applicationContext = applicationContext;
        this.modelCredentialResolver = modelCredentialResolver;
    }

    /**
     * 把一组 api + model 注册为运行时 Bean。
     * <p>
     * 若 api_key 经 {@link ModelCredentialResolver} 解析后为空（既无明文也未配置对应环境变量），
     * 则跳过注册——此时即便注册了 Bean，调用也会因无凭据而失败，不如早失败更清晰。
     *
     * @param api   API 接口配置（base_url / api_key / path）
     * @param model 模型配置（model_name / model_id）
     */
    public void register(AiClientApi api, AiClientModel model) {
        if (api == null || model == null) {
            return;
        }
        String apiKey = modelCredentialResolver.resolve(api.getApiKey());
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("跳过未配置凭据的模型，modelId={}，apiId={}", model.getModelId(), api.getApiId());
            return;
        }
        String baseUrl = trimTrailingSlash(api.getBaseUrl());
        String completionsPath = ensureLeadingSlash(firstNonBlank(api.getCompletionsPath(), "/v1/chat/completions"));
        String embeddingsPath = ensureLeadingSlash(firstNonBlank(api.getEmbeddingsPath(), "/embeddings"));
        try {
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .completionsPath(completionsPath)
                    .embeddingsPath(embeddingsPath)
                    .build();
            registerBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(api.getApiId()), OpenAiApi.class, openAiApi);

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder().model(model.getModelName()).build())
                    .retryTemplate(ModelRetryPolicy.noRetry())
                    .build();
            registerBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(model.getModelId()), OpenAiChatModel.class, chatModel);
            log.info("模型 Bean 已注册，modelId={}，apiId={}，baseUrl={}{}，model={}",
                    model.getModelId(), api.getApiId(), baseUrl, completionsPath, model.getModelName());
        } catch (Exception e) {
            log.warn("模型 Bean 注册失败，modelId={}，apiId={}，原因={}", model.getModelId(), api.getApiId(), e.getMessage(), e);
        }
    }

    /**
     * 注销模型与其 API 的运行时 Bean。删除模型时调用，避免残留 Bean 被后续对话取到。
     */
    public void unregister(String modelId, String apiId) {
        unregisterBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId));
        // API 可能被多个模型共享，仅当没有其它启用模型再引用它时才注销。
        if (apiId != null && !apiId.isBlank() && !hasEnabledModelOnApi(apiId, modelId)) {
            unregisterBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(apiId));
        }
    }

    /**
     * 启动兜底：把数据库里所有启用的模型注册为 Bean。
     * <p>
     * 仅注册“当前 BeanFactory 中尚不存在”的模型，避免覆盖 armory 已注册的带 toolCallbacks 版本——
     * 当 {@code spring.ai.agent.auto-config} 开启时，armory 在 {@code ApplicationReadyEvent} 走全量装配；
     * 本方法在其后运行，补齐 armory 未覆盖到的模型，保证即使关闭 auto-config 也有可用 Bean。
     */
    public void registerAllEnabled() {
        List<AiClientModel> enabledModels = aiClientModelDao.queryEnabledModels();
        if (enabledModels == null || enabledModels.isEmpty()) {
            log.info("没有启用的模型需要注册");
            return;
        }
        int registered = 0;
        for (AiClientModel model : enabledModels) {
            String modelBeanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(model.getModelId());
            if (applicationContext.containsBean(modelBeanName)) {
                continue;
            }
            AiClientApi api = aiClientApiDao.queryByApiId(model.getApiId());
            if (api == null) {
                log.warn("跳过无 API 关联的模型，modelId={}，apiId={}", model.getModelId(), model.getApiId());
                continue;
            }
            register(api, model);
            registered++;
        }
        log.info("模型 Bean 启动兜底注册完成，本次新增 {} 个，启用模型总数 {}", registered, enabledModels.size());
    }

    private boolean hasEnabledModelOnApi(String apiId, String excludeModelId) {
        List<AiClientModel> models = aiClientModelDao.queryByApiId(apiId);
        if (models == null || models.isEmpty()) {
            return false;
        }
        for (AiClientModel model : models) {
            if (excludeModelId != null && excludeModelId.equals(model.getModelId())) {
                continue;
            }
            if (Integer.valueOf(1).equals(model.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition beanDefinition = builder.getRawBeanDefinition();
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }
        beanFactory.registerBeanDefinition(beanName, beanDefinition);
    }

    private void unregisterBean(String beanName) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
            log.info("模型 Bean 已注销: {}", beanName);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
