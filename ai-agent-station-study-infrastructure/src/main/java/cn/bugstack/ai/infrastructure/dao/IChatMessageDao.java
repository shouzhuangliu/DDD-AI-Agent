package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IChatMessageDao {
    int insert(ChatMessage msg);
    ChatMessage queryById(@Param("id") Long id);
    List<ChatMessage> queryBySessionId(@Param("sessionId") String sessionId);
    ChatMessage queryByToolCallId(@Param("toolCallId") String toolCallId);
    long countBySessionId(@Param("sessionId") String sessionId);
    int updateCompressed(@Param("id") Long id, @Param("compressed") int compressed);
}