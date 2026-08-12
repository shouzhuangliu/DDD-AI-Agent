package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryIndexOutbox;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface IAgentMemoryIndexOutboxDao {
    int insert(AgentMemoryIndexOutbox event);
    AgentMemoryIndexOutbox queryClaimable();
    int claim(@Param("id") Long id);
    int markDone(@Param("eventId") String eventId);
    int markRetry(@Param("eventId") String eventId, @Param("error") String error, @Param("nextRetryAt") LocalDateTime nextRetryAt);
    int markFailed(@Param("eventId") String eventId, @Param("error") String error);
}
