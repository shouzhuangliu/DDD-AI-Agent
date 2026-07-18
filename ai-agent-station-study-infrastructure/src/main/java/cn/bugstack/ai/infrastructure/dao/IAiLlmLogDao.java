package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IAiLlmLogDao {
    int insert(AiLlmLog record);
    AiLlmLog queryById(@Param("id") Long id);
    List<AiLlmLog> queryBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);
    List<AiLlmLog> queryByAgentId(@Param("agentId") String agentId, @Param("limit") int limit);
    List<AiLlmLog> queryRecent(@Param("limit") int limit);
    long countAll();
    long countByAgentId(@Param("agentId") String agentId);
    long countByStatus(@Param("status") String status);
}