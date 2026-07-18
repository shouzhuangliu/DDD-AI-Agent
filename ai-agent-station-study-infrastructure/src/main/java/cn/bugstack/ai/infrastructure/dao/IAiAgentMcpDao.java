package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentMcp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentMcpDao {
    int insert(AiAgentMcp record);
    List<AiAgentMcp> queryByAgentId(@Param("agentId") String agentId);
    int deleteByAgentId(@Param("agentId") String agentId);
    int deleteByAgentAndMcp(@Param("agentId") String agentId, @Param("mcpId") String mcpId);
}