package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IAiFeedbackDao {
    int insert(AiFeedback record);
    List<AiFeedback> queryRecent(@Param("limit") int limit);
    long countToday();
    long countByResolved(@Param("resolved") int resolved);
    List<AiFeedback> queryByAgentId(@Param("agentId") String agentId, @Param("limit") int limit);
    List<AiFeedback> queryExplicitByAgentId(@Param("agentId") String agentId, @Param("limit") int limit);
    long countExplicitByAgentId(@Param("agentId") String agentId);
    long countExplicitTodayByAgentId(@Param("agentId") String agentId);
    long countNegativeByAgentId(@Param("agentId") String agentId);
}
