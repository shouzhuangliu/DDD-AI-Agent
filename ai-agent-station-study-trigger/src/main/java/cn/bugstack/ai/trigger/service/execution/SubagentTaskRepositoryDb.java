package cn.bugstack.ai.trigger.service.execution;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskState;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SubagentTaskRepositoryDb implements ISubagentTaskRepository {

    @Resource
    private ISubagentTaskDao taskDao;

    @Override
    public void create(SubagentTaskState state) {
        taskDao.insert(toPo(state));
    }

    @Override
    public void markRunning(String taskId) {
        taskDao.markRunning(taskId);
    }

    @Override
    public void markCancelRequested(String taskId) {
        taskDao.markCancelRequested(taskId);
    }

    @Override
    public void finish(SubagentTaskState state) {
        taskDao.finish(toPo(state));
    }

    @Override
    public SubagentTaskState findByTaskId(String taskId) {
        SubagentTask task = taskDao.queryByTaskId(taskId);
        return task == null ? null : toState(task);
    }

    @Override
    public void markInterruptedTasks() {
        taskDao.markInterruptedTasks();
    }

    private SubagentTask toPo(SubagentTaskState state) {
        return SubagentTask.builder()
                .taskId(state.getTaskId()).executionId(state.getExecutionId()).agentId(state.getAgentId())
                .description(state.getDescription()).status(state.getStatus()).result(state.getResult())
                .errorMessage(state.getErrorMessage()).cancelRequested(state.isCancelRequested())
                .startedAt(state.getStartedAt()).completedAt(state.getCompletedAt()).build();
    }

    private SubagentTaskState toState(SubagentTask task) {
        return SubagentTaskState.builder()
                .taskId(task.getTaskId()).executionId(task.getExecutionId()).agentId(task.getAgentId())
                .description(task.getDescription()).status(task.getStatus()).result(task.getResult())
                .errorMessage(task.getErrorMessage()).cancelRequested(Boolean.TRUE.equals(task.getCancelRequested()))
                .startedAt(task.getStartedAt()).completedAt(task.getCompletedAt()).build();
    }
}
