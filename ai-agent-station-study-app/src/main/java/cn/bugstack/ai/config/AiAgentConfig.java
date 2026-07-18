package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnBean(name = "pgVectorJdbcTemplate")
@EnableConfigurationProperties(ReActToolProperties.class)
public class AiAgentConfig {

    /**
     * -- 删除旧的表（如果存在）
     * DROP TABLE IF EXISTS public.vector_store_openai;
     * <p>
     * -- 创建新的表，使用UUID作为主键
     * CREATE TABLE public.vector_store_openai (
     * id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     * content TEXT NOT NULL,
     * metadata JSONB,
     * embedding VECTOR(1536)
     * );
     * <p>
     * SELECT * FROM vector_store_openai
     */

    @Bean("vectorStore")
    public PgVectorStore pgVectorStore(@Value("${spring.ai.openai.base-url}") String baseUrl,
                                       @Value("${spring.ai.openai.api-key}") String apiKey,
                                       @Value("${spring.ai.openai.embedding.options.model}") String modelName, // 👈 1. 读取 yml 中的模型名称
                                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        // 2. 👈 构建 Embedding 配置参数，指定模型名称
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(modelName)
                .build();
        // 3. 👈 实例化模型时，将 options 传进去！(MetadataMode.EMBED 是标准配置)
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("vector_store")
                .dimensions(1024) // 🚨 极度重要警告！请看下文解释
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

}