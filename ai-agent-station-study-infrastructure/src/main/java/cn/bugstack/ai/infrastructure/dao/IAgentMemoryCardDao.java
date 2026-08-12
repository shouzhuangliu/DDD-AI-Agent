package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IAgentMemoryCardDao {
    int insert(AgentMemoryCard card);
    int supersedeByKey(@Param("agentId") String agentId, @Param("memoryKey") String memoryKey);
    int retireByCaseId(@Param("agentId") String agentId, @Param("caseId") String caseId);
    AgentMemoryCard queryLatestByKey(@Param("agentId") String agentId, @Param("memoryKey") String memoryKey);
    List<AgentMemoryCard> searchPublishedIndex(@Param("agentId") String agentId, @Param("query") String query, @Param("limit") int limit);
    List<AgentMemoryCard> queryPublishedByMemoryIds(@Param("agentId") String agentId, @Param("memoryIds") List<String> memoryIds);
    List<AgentMemoryCard> queryPublishedByAgent(@Param("agentId") String agentId);
}
