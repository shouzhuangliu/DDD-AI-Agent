package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCandidate;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IAgentMemoryCandidateDao {
    int insertIgnore(AgentMemoryCandidate candidate);
    AgentMemoryCandidate queryByCandidateId(@Param("agentId") String agentId, @Param("candidateId") String candidateId);
    AgentMemoryCandidate queryByUniqueSource(@Param("agentId") String agentId,
                                             @Param("memoryType") String memoryType,
                                             @Param("memoryKey") String memoryKey,
                                             @Param("sourceType") String sourceType,
                                             @Param("sourceId") String sourceId);
    List<AgentMemoryCandidate> queryByStatus(@Param("agentId") String agentId, @Param("status") String status, @Param("limit") int limit);
    int transition(@Param("agentId") String agentId, @Param("candidateId") String candidateId,
                   @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                   @Param("reviewedBy") String reviewedBy, @Param("reviewComment") String reviewComment,
                   @Param("reviewedAt") LocalDateTime reviewedAt);
}
