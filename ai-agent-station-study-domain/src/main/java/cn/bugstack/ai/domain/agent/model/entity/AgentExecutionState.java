package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 一次用户消息对应的一次 Agent 执行状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionState {

    private String executionId;
    private String sessionId;
    private String agentId;
    private String modelId;
    private String routeType;
    private String status;
    private int currentCycle;
    private int currentStep;
    private int maxCycles;
    private int maxSteps;
    private String stateJson;
    private String lastAssistantContent;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
