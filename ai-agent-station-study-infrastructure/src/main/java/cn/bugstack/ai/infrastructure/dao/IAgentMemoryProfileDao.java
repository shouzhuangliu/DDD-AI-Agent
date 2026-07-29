package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAgentMemoryProfileDao {
    AgentMemoryProfile queryLatest(@Param("agentId") String agentId);
    int insert(AgentMemoryProfile profile);
}
