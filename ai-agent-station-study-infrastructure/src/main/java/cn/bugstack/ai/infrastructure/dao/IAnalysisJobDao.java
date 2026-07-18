package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AnalysisJob;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IAnalysisJobDao {
    int insertIgnore(AnalysisJob job);
    AnalysisJob queryClaimable();
    int claim(@Param("id") Long id, @Param("leaseUntil") java.time.LocalDateTime leaseUntil);
    int markComplete(@Param("id") Long id);
    int markFailure(@Param("id") Long id, @Param("status") String status, @Param("errorMessage") String errorMessage);
}
