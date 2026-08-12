package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IAgentMemoryChangeLogDao {
    int insert(AgentMemoryChangeLog log);
    List<AgentMemoryChangeLog> queryByMemoryId(@Param("agentId") String agentId, @Param("memoryId") String memoryId,
                                               @Param("limit") int limit);
}
