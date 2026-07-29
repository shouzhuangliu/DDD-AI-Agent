package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAgentExecutionDao {

    int insert(AgentExecution execution);

    AgentExecution queryByExecutionId(@Param("executionId") String executionId);

    int updateProgress(@Param("executionId") String executionId,
                       @Param("currentCycle") int currentCycle,
                       @Param("currentStep") int currentStep,
                       @Param("stateJson") String stateJson);

    int finish(@Param("executionId") String executionId,
               @Param("status") String status,
               @Param("lastAssistantContent") String lastAssistantContent,
               @Param("errorMessage") String errorMessage);
}
