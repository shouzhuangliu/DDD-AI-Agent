package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskState;

/** Persistence port for Subagent task lifecycle state. */
public interface ISubagentTaskRepository {

    void create(SubagentTaskState state);

    void markRunning(String taskId);

    void markCancelRequested(String taskId);

    void finish(SubagentTaskState state);

    SubagentTaskState findByTaskId(String taskId);

    void markInterruptedTasks();
}
