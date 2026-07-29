package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.AgentExecutionState;

/** Agent 执行状态持久化端口。 */
public interface IAgentExecutionRepository {

    void create(AgentExecutionState state);

    AgentExecutionState findByExecutionId(String executionId);

    int updateProgress(String executionId, int currentCycle, int currentStep, String stateJson);

    int finish(String executionId, String status, String lastAssistantContent, String errorMessage);
}
