package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.config.AiAgentConfig;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.junit.Assert.assertFalse;

public class AiAgentConfigConditionalTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiAgentConfig.class, StubPgVectorDataSourceConfiguration.class)
            .withPropertyValues(
                    "agent.memory.long-term.provider=noop",
                    "spring.ai.openai.base-url=https://example.com",
                    "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.embedding.options.model=test-embedding-model"
            );

    @Test
    public void doesNotCreatePgVectorStoreWhenLongTermMemoryProviderIsNoop() {
        contextRunner.run(context ->
                assertFalse("pgVectorStore should stay optional when long-term memory is disabled",
                        context.containsBean("pgVectorStore")));
    }

    @Configuration
    static class StubPgVectorDataSourceConfiguration {
        @Bean("pgVectorDataSource")
        public DataSource pgVectorDataSource() {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource();
        }
    }
}
