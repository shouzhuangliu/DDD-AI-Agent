package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.CaseEvaluationSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ICaseEvaluationSnapshotDao {
    int insertIgnore(CaseEvaluationSnapshot snapshot);

    CaseEvaluationSnapshot queryLatest(@Param("agentId") String agentId,
                                       @Param("sessionId") String sessionId);
}
