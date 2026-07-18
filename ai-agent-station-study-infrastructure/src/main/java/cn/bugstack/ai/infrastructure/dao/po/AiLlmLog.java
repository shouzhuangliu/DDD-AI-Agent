package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class AiLlmLog {
    private Long id;
    private String sessionId;
    private String agentId;
    private String modelName;
    private String mode;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer durationMs;
    private String status;
    private String errorMessage;
    private Integer historyMsgCount;
    private Integer foldedMsgCount;
    private Integer systemPromptLen;
    private Integer userMessageLen;
    private Integer assistantResponseLen;
    private LocalDateTime createdAt;
}