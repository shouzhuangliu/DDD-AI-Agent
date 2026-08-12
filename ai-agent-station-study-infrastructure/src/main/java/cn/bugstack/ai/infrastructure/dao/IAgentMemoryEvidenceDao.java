package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryEvidence;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IAgentMemoryEvidenceDao {
    int insertIgnore(AgentMemoryEvidence evidence);
    List<AgentMemoryEvidence> queryByOwner(@Param("agentId") String agentId,
                                           @Param("ownerType") String ownerType,
                                           @Param("ownerId") String ownerId);
}
