package cn.bugstack.ai.infrastructure.dao;
import cn.bugstack.ai.infrastructure.dao.po.MemoryState;
import org.apache.ibatis.annotations.*;
@Mapper public interface IMemoryStateDao { int insert(MemoryState state); MemoryState queryLatest(@Param("sessionId") String sessionId); }
