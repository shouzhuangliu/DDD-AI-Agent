package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IAiSessionDao {
    int insert(AiSession record);
    int updateBySessionId(AiSession record);
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);
    int softDelete(@Param("sessionId") String sessionId);
    int incrementMessageCount(@Param("sessionId") String sessionId);
    int touch(@Param("sessionId") String sessionId, @Param("preview") String preview, @Param("modelId") String modelId);
    AiSession queryBySessionId(@Param("sessionId") String sessionId);
    AiSession queryByAgentAndSession(@Param("agentId") String agentId, @Param("sessionId") String sessionId);
    List<AiSession> queryByAgentId(@Param("agentId") String agentId, @Param("limit") int limit);
    List<AiSession> queryRecent(@Param("limit") int limit);
    long countByAgentId(@Param("agentId") String agentId);
}
