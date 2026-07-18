package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface IFeedbackEvaluationJobDao {
    int insertIgnore(FeedbackEvaluationJob job);
    FeedbackEvaluationJob queryClaimable();
    int claim(@Param("id") Long id, @Param("leaseUntil") LocalDateTime leaseUntil);
    int markComplete(@Param("id") Long id);
    int markFailure(@Param("id") Long id, @Param("status") String status, @Param("errorMessage") String errorMessage);
}
