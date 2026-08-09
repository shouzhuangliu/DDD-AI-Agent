package cn.bugstack.ai.domain.agent.service.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 根据模型上下文窗口计算本次推理的输入预算。 */
@Component
@Primary
public class ContextBudgetPolicy {

    private final Map<String, ModelContextProfile> profiles;
    private final ModelContextProfile defaultProfile;
    private final TokenBudgetEstimator estimator = new TokenBudgetEstimator();

    public ContextBudgetPolicy(ModelContextProperties properties) {
        this(properties.toProfiles(), properties.defaultProfile());
    }

    public ContextBudgetPolicy(Map<String, ModelContextProfile> profiles,
                               ModelContextProfile defaultProfile) {
        this.profiles = profiles == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        this.defaultProfile = defaultProfile == null ? ModelContextProfile.safeDefault() : defaultProfile;
    }

    public BudgetDecision decide(String modelId, String systemPrompt,
                                 String toolDescription, List<Map<String, Object>> messages) {
        ModelContextProfile profile = profileFor(modelId);
        int effective = Math.max(1, profile.contextWindowTokens() - profile.maxOutputTokens()
                - profile.safetyMarginTokens());
        int current = estimator.estimate(systemPrompt) + estimator.estimate(toolDescription);
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                if (message == null) continue;
                current += estimator.estimate(String.valueOf(message.getOrDefault("content", "")));
                current += estimator.estimate(String.valueOf(message.getOrDefault("tool_arguments", "")));
                Object toolCalls = message.get("tool_calls");
                if (toolCalls != null) current += estimator.estimate(String.valueOf(toolCalls));
            }
        }
        int soft = Math.max(1, (int) Math.floor(effective * profile.softSummaryRatio()));
        int hard = Math.max(soft + 1, (int) Math.floor(effective * profile.hardFoldRatio()));
        return new BudgetDecision(modelId, profile, current, effective, soft, hard,
                current > soft, current > hard);
    }

    private ModelContextProfile profileFor(String modelId) {
        if (modelId == null || modelId.isBlank()) return defaultProfile;
        ModelContextProfile exact = profiles.get(modelId);
        if (exact != null) return exact;
        String normalized = modelId.trim().toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, ModelContextProfile> entry : profiles.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return entry.getValue();
            }
        }
        return defaultProfile;
    }

    public record BudgetDecision(String modelId, ModelContextProfile profile,
                                 int currentInputTokens, int effectiveInputTokens,
                                 int softSummaryThreshold, int hardFoldThreshold,
                                 boolean shouldSummarize, boolean shouldFold) {
    }

    @ConfigurationProperties(prefix = "agent.memory.context")
    @Component
    public static class ModelContextProperties {
        private int defaultContextWindowTokens = 32_768;
        private int defaultMaxOutputTokens = 4_096;
        private double softSummaryRatio = 0.60d;
        private double hardFoldRatio = 0.85d;
        private int safetyMarginTokens = 1_024;
        private Map<String, ModelProfileProperties> models = new LinkedHashMap<>();

        public Map<String, ModelContextProfile> toProfiles() {
            Map<String, ModelContextProfile> result = new LinkedHashMap<>();
            if (models != null) models.forEach((id, value) -> result.put(id, value.toProfile(this)));
            return result;
        }

        public ModelContextProfile defaultProfile() {
            return new ModelContextProfile(defaultContextWindowTokens, defaultMaxOutputTokens,
                    softSummaryRatio, hardFoldRatio, safetyMarginTokens);
        }

        public int getDefaultContextWindowTokens() { return defaultContextWindowTokens; }
        public void setDefaultContextWindowTokens(int value) { this.defaultContextWindowTokens = value; }
        public int getDefaultMaxOutputTokens() { return defaultMaxOutputTokens; }
        public void setDefaultMaxOutputTokens(int value) { this.defaultMaxOutputTokens = value; }
        public double getSoftSummaryRatio() { return softSummaryRatio; }
        public void setSoftSummaryRatio(double value) { this.softSummaryRatio = value; }
        public double getHardFoldRatio() { return hardFoldRatio; }
        public void setHardFoldRatio(double value) { this.hardFoldRatio = value; }
        public int getSafetyMarginTokens() { return safetyMarginTokens; }
        public void setSafetyMarginTokens(int value) { this.safetyMarginTokens = value; }
        public Map<String, ModelProfileProperties> getModels() { return models; }
        public void setModels(Map<String, ModelProfileProperties> value) { this.models = value; }
    }

    public static class ModelProfileProperties {
        private Integer contextWindowTokens;
        private Integer maxOutputTokens;
        private Double softSummaryRatio;
        private Double hardFoldRatio;
        private Integer safetyMarginTokens;

        private ModelContextProfile toProfile(ModelContextProperties defaults) {
            return new ModelContextProfile(
                    contextWindowTokens == null ? defaults.defaultContextWindowTokens : contextWindowTokens,
                    maxOutputTokens == null ? defaults.defaultMaxOutputTokens : maxOutputTokens,
                    softSummaryRatio == null ? defaults.softSummaryRatio : softSummaryRatio,
                    hardFoldRatio == null ? defaults.hardFoldRatio : hardFoldRatio,
                    safetyMarginTokens == null ? defaults.safetyMarginTokens : safetyMarginTokens);
        }

        public Integer getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(Integer value) { this.contextWindowTokens = value; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer value) { this.maxOutputTokens = value; }
        public Double getSoftSummaryRatio() { return softSummaryRatio; }
        public void setSoftSummaryRatio(Double value) { this.softSummaryRatio = value; }
        public Double getHardFoldRatio() { return hardFoldRatio; }
        public void setHardFoldRatio(Double value) { this.hardFoldRatio = value; }
        public Integer getSafetyMarginTokens() { return safetyMarginTokens; }
        public void setSafetyMarginTokens(Integer value) { this.safetyMarginTokens = value; }
    }
}
