package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryCard {
    private Long id; private String memoryId; private String agentId; private String memoryType; private String memoryKey;
    private Integer version; private String title; private String description; private String contentJson; private String status;
    private Integer isDeleted; private Integer importance; private Integer pinned; private String updatedReason;
    private String sourceCandidateId; private String sourceCaseId; private LocalDateTime effectiveAt; private LocalDateTime expiresAt;
    private String publishedBy; private LocalDateTime publishedAt; private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
