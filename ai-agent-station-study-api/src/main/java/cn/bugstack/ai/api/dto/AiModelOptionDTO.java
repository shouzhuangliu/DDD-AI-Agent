package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelOptionDTO {
    private String modelId;
    private String modelName;
    private String modelType;
    private String providerName;
    private boolean configured;
}
