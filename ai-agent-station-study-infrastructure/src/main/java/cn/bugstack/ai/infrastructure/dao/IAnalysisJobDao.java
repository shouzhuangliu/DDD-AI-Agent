package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AnalysisJob;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IAnalysisJobDao {
    int insertIgnore(AnalysisJob job);
    int refreshPendingSession(@Param("agentId") String agentId,
                              @Param("sessionId") String sessionId,
                              @Param("policyVersion") String policyVersion,
                              @Param("assistantMessageId") Long assistantMessageId,
                              @Param("availableAt") java.time.LocalDateTime availableAt);
    AnalysisJob queryClaimable();
    int claim(@Param("id") Long id, @Param("leaseUntil") java.time.LocalDateTime leaseUntil);
    int markComplete(@Param("id") Long id);
    int markFailure(@Param("id") Long id, @Param("status") String status, @Param("errorMessage") String errorMessage);
    int deferFailure(@Param("id") Long id, @Param("status") String status,
                     @Param("errorMessage") String errorMessage,
                     @Param("leaseUntil") java.time.LocalDateTime leaseUntil);
}
