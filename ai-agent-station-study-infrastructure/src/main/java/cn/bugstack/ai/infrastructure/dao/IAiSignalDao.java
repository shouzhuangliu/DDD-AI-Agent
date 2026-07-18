package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiSignal;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IAiSignalDao {
    int insert(AiSignal signal);
    List<AiSignal> queryByAgentId(@Param("agentId") String agentId, @Param("limit") int limit);
    long countOpenByAgentId(@Param("agentId") String agentId);
}
