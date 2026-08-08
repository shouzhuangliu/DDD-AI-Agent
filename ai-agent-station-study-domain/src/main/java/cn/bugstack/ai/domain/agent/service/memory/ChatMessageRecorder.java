package cn.bugstack.ai.domain.agent.service.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 聊天消息、LLM 日志、Case/反馈 查询接口。
 * 域层 Noop 空实现，trigger 层 @Primary 覆盖为真实 DB 实现。
 */
public interface ChatMessageRecorder {

    // ========== 消息记录 ==========
    void recordUser(String sessionId, String agentId, int turn, String content);
    void recordAssistant(String sessionId, String agentId, int turn, int step, String content, String toolCallsJson);
    void recordTool(String sessionId, String agentId, int turn, int step, String toolCallId, String toolName, String toolArguments, String content);
    List<HistoryMessage> getHistory(String sessionId);
    ToolCallExchange findToolExchange(String sessionId, String toolCallId);
    String findByToolCallId(String toolCallId);
    void markCompressed(long id);

    // ========== LLM 日志 ==========
    void recordLlmLog(LlmLogEntry entry);
    List<?> queryLlmLogs(int limit);
    Object queryLlmLogById(Long id);

    // ========== Case / 反馈查询 ==========
    /** 查询 Case 列表 */
    Object queryCases(String keyword, int limit);
    /** 查询反馈列表 */
    Object queryFeedback(int limit, String agentId);

    @Data @Builder @AllArgsConstructor @NoArgsConstructor
    class LlmLogEntry {
        private String sessionId; private String agentId; private String modelName; private String mode;
        private int inputTokens; private int outputTokens; private int totalTokens; private int durationMs;
        private String status; private String errorMessage;
        private int historyMsgCount; private int foldedMsgCount;
        private int systemPromptLen; private int userMessageLen; private int assistantResponseLen;
    }
}
