package cn.bugstack.ai.domain.agent.service.armory.business.data;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Armory 数据加载策略 Bean 注册。
 */
@Configuration
public class ArmoryLoadDataStrategyConfiguration {

    @Bean("aiClientApiLoadDataStrategy")
    public ILoadDataStrategy aiClientApiLoadDataStrategy() {
        return new NoopArmoryLoadDataStrategy();
    }

    @Bean("aiClientSystemPromptLoadDataStrategy")
    public ILoadDataStrategy aiClientSystemPromptLoadDataStrategy() {
        return new NoopArmoryLoadDataStrategy();
    }

    @Bean("aiClientToolMCPLoadDataStrategy")
    public ILoadDataStrategy aiClientToolMCPLoadDataStrategy() {
        return new NoopArmoryLoadDataStrategy();
    }

    @Bean("aiClientAdvisorLoadDataStrategy")
    public ILoadDataStrategy aiClientAdvisorLoadDataStrategy() {
        return new NoopArmoryLoadDataStrategy();
    }
}
