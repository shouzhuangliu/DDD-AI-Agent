package cn.bugstack.ai.trigger.service.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把数据库里所有启用的模型装配为 Spring Bean。
 * <p>
 * armory 树（{@code AiAgentAutoConfiguration}）在 {@code ApplicationReadyEvent} 做全量装配，但依赖
 * {@code spring.ai.agent.auto-config.enabled=true} 且 client-ids 非空；关闭后启动期不会注册任何模型 Bean，
 * Chat / ReAct 执行时 {@code getBean("ai_client_model_<id>")} 会抛 NoSuchBeanDefinitionException。
 * <p>
 * 本 Runner 以最低优先级运行，保证在 armory 之后执行；{@link ChatModelBeanRegistrar#registerAllEnabled()}
 * 内部对已存在的 Bean 跳过，因此不会覆盖 armory 已注册的带 toolCallbacks 版本，仅做兜底补齐。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ChatModelBootstrapRunner implements ApplicationRunner {

    private final ChatModelBeanRegistrar registrar;

    public ChatModelBootstrapRunner(ChatModelBeanRegistrar registrar) {
        this.registrar = registrar;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            registrar.registerAllEnabled();
        } catch (Exception e) {
            log.error("模型 Bean 启动兜底装配失败", e);
        }
    }
}
