package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class AiSession {
    private Long id;
    private String sessionId;
    private String agentId;
    private String title;
    private Integer messageCount;
    private Integer status;
    private String modelId;
    private String preview;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastMessageAt;
}
