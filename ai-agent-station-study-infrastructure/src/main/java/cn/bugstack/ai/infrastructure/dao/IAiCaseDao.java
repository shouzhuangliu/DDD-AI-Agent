package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IAiCaseDao {
    int insert(AiCase record);
    int updateByCaseId(AiCase record);
    AiCase queryByCaseId(@Param("caseId") String caseId);
    List<AiCase> queryTop(@Param("limit") int limit);
    List<AiCase> queryByStatus(@Param("status") String status);
    long countByStatus(@Param("status") String status);
    long countAll();
    int incrementFrequency(@Param("caseId") String caseId);
    List<AiCase> queryByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);
    List<AiCase> queryTopByAgent(@Param("agentId") String agentId, @Param("limit") int limit);
    List<AiCase> queryByAgentAndStatus(@Param("agentId") String agentId, @Param("status") String status, @Param("limit") int limit);
    AiCase queryByAgentAndCaseId(@Param("agentId") String agentId, @Param("caseId") String caseId);
    long countByAgent(@Param("agentId") String agentId);
    long countByAgentAndStatus(@Param("agentId") String agentId, @Param("status") String status);
    int updateAnalysis(AiCase record);
    int transitionStatus(@Param("agentId") String agentId,
                         @Param("caseId") String caseId,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus,
                         @Param("owner") String owner,
                         @Param("resolution") String resolution);
}
