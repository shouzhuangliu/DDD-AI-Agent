package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryIndexOutbox {
    private Long id; private String eventId; private String agentId; private String memoryId; private Integer memoryVersion;
    private String eventType; private String payloadJson; private String status; private Integer attempts;
    private LocalDateTime nextRetryAt; private String lastError; private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
