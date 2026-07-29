package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.SubagentTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ISubagentTaskDao {
    int insert(SubagentTask task);
    int markRunning(@Param("taskId") String taskId);
    int markCancelRequested(@Param("taskId") String taskId);
    int finish(SubagentTask task);
    SubagentTask queryByTaskId(@Param("taskId") String taskId);

    int markInterruptedTasks();
}
