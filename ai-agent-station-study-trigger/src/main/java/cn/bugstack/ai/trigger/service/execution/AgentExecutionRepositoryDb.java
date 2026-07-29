package cn.bugstack.ai.trigger.service.execution;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentExecutionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentExecutionState;
import cn.bugstack.ai.infrastructure.dao.IAgentExecutionDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 执行状态的数据库适配器，业务层不直接操作 MyBatis。 */
@Component
@Slf4j
public class AgentExecutionRepositoryDb implements IAgentExecutionRepository {

    @Resource
    private IAgentExecutionDao executionDao;

    @Override
    public void create(AgentExecutionState state) {
        try {
            executionDao.insert(toPo(state));
        } catch (RuntimeException e) {
            log.warn("保存 Agent 执行状态失败，继续执行对话: {}", e.getMessage());
        }
    }

    @Override
    public AgentExecutionState findByExecutionId(String executionId) {
        try {
            AgentExecution execution = executionDao.queryByExecutionId(executionId);
            return execution == null ? null : toState(execution);
        } catch (RuntimeException e) {
            log.warn("查询 Agent 执行状态失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int updateProgress(String executionId, int currentCycle, int currentStep, String stateJson) {
        try {
            return executionDao.updateProgress(executionId, currentCycle, currentStep, stateJson);
        } catch (RuntimeException e) {
            log.warn("更新 Agent 执行进度失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int finish(String executionId, String status, String lastAssistantContent, String errorMessage) {
        try {
            return executionDao.finish(executionId, status, lastAssistantContent, errorMessage);
        } catch (RuntimeException e) {
            log.warn("结束 Agent 执行状态失败: {}", e.getMessage());
            return 0;
        }
    }

    private AgentExecution toPo(AgentExecutionState state) {
        return AgentExecution.builder()
                .executionId(state.getExecutionId()).sessionId(state.getSessionId()).agentId(state.getAgentId())
                .modelId(state.getModelId()).routeType(state.getRouteType()).status(state.getStatus())
                .currentCycle(state.getCurrentCycle()).currentStep(state.getCurrentStep())
                .maxCycles(state.getMaxCycles()).maxSteps(state.getMaxSteps()).stateJson(state.getStateJson())
                .lastAssistantContent(state.getLastAssistantContent()).errorMessage(state.getErrorMessage())
                .startedAt(state.getStartedAt()).updatedAt(state.getUpdatedAt()).completedAt(state.getCompletedAt())
                .build();
    }

    private AgentExecutionState toState(AgentExecution execution) {
        return AgentExecutionState.builder()
                .executionId(execution.getExecutionId()).sessionId(execution.getSessionId()).agentId(execution.getAgentId())
                .modelId(execution.getModelId()).routeType(execution.getRouteType()).status(execution.getStatus())
                .currentCycle(value(execution.getCurrentCycle())).currentStep(value(execution.getCurrentStep()))
                .maxCycles(value(execution.getMaxCycles())).maxSteps(value(execution.getMaxSteps()))
                .stateJson(execution.getStateJson()).lastAssistantContent(execution.getLastAssistantContent())
                .errorMessage(execution.getErrorMessage()).startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt()).updatedAt(execution.getUpdatedAt()).build();
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
