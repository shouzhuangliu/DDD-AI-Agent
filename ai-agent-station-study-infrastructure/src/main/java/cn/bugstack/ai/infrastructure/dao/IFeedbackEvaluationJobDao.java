package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IFeedbackEvaluationJobDao {
    int insertIgnore(FeedbackEvaluationJob job);
}
