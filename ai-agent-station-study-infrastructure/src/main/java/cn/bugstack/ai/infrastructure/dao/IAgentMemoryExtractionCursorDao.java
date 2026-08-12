package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryExtractionCursor;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IAgentMemoryExtractionCursorDao {
    AgentMemoryExtractionCursor query(@Param("agentId") String agentId, @Param("sessionId") String sessionId);
    int insertIgnore(AgentMemoryExtractionCursor cursor);
    int advance(@Param("agentId") String agentId, @Param("sessionId") String sessionId,
                @Param("expectedMessageId") long expectedMessageId, @Param("nextMessageId") long nextMessageId);
    int markFailure(@Param("agentId") String agentId, @Param("sessionId") String sessionId, @Param("error") String error);
}
