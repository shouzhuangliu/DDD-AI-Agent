package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentTool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentToolDao {
    int insert(AiAgentTool record);
    List<AiAgentTool> queryByAgentId(@Param("agentId") String agentId);
    int deleteByAgentId(@Param("agentId") String agentId);
}
