package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryExtractionCursor {
    private Long id; private String agentId; private String sessionId; private Long lastMessageId;
    private Integer version; private String lastStatus; private Integer retryCount; private String lastError;
    private LocalDateTime updatedAt;
}
