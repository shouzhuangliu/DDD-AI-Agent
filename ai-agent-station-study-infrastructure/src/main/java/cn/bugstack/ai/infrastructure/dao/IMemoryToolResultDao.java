package cn.bugstack.ai.infrastructure.dao;
import cn.bugstack.ai.infrastructure.dao.po.MemoryToolResult;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface IMemoryToolResultDao {
    int insertIgnore(MemoryToolResult result); List<MemoryToolResult> queryBySession(@Param("sessionId") String sessionId,@Param("limit") int limit);
}
