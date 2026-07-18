package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentSkillDao {
    int insert(AiAgentSkill record);
    List<AiAgentSkill> queryByAgentId(@Param("agentId") String agentId);
    int deleteByAgentId(@Param("agentId") String agentId);
    int deleteByAgentAndSkill(@Param("agentId") String agentId, @Param("skillId") String skillId);
}