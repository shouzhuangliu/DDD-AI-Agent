package cn.bugstack.ai.infrastructure.dao;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface IMemorySummaryDao {
    int insert(MemorySummary summary); MemorySummary queryLatest(@Param("sessionId") String sessionId);
    int supersede(@Param("sessionId") String sessionId); List<MemorySummary> queryByAgent(@Param("agentId") String agentId,@Param("limit") int limit);
}
