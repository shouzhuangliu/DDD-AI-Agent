package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MemorySummary {
    private Long id; private String agentId; private String sessionId; private Integer version;
    private Long startMessageId; private Long endMessageId; private String summary; private String modelId;
    private Integer tokenCount; private String status; private LocalDateTime createdAt;
}
