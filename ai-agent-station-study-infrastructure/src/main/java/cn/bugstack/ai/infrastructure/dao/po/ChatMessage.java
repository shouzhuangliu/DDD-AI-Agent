package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class ChatMessage {
    private Long id;
    private String sessionId;
    private String agentId;
    private Integer turn;
    private Integer step;
    private String role;           // user / assistant / tool
    private String content;        // DB原件，永不压缩
    private String toolCallId;
    private String toolName;
    private String toolArguments;
    private String toolCallsJson;  // assistant的tool_calls JSON数组
    private Integer compressed;
    private LocalDateTime createdAt;
}