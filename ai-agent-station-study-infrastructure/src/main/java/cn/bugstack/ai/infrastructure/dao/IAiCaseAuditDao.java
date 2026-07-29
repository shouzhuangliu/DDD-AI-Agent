package cn.bugstack.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface IAiCaseAuditDao {

    List<Map<String, Object>> queryEvidence(@Param("agentId") String agentId,
                                            @Param("caseId") String caseId);

    List<Map<String, Object>> queryScoreSnapshots(@Param("agentId") String agentId,
                                                  @Param("caseId") String caseId);

    List<Map<String, Object>> queryReviews(@Param("agentId") String agentId,
                                           @Param("caseId") String caseId);

    int insertReview(@Param("caseId") String caseId,
                     @Param("agentId") String agentId,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("actor") String actor,
                     @Param("reason") String reason);
}
