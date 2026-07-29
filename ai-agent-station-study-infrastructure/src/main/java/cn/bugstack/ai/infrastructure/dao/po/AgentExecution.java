package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecution {
    private Long id;
    private String executionId;
    private String sessionId;
    private String agentId;
    private String modelId;
    private String routeType;
    private String status;
    private Integer currentCycle;
    private Integer currentStep;
    private Integer maxCycles;
    private Integer maxSteps;
    private String stateJson;
    private String lastAssistantContent;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
