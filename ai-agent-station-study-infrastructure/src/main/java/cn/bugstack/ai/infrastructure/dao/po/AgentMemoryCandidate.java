package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryCandidate {
    private Long id; private String candidateId; private String agentId; private String memoryType;
    private String memoryKey; private String title; private String summary; private String contentJson;
    private String sourceType; private String sourceId; private String sourceSessionId; private String sourceCaseId;
    private Integer confidence; private String status; private String extractionModelId; private String promptVersion;
    private String reviewedBy; private LocalDateTime reviewedAt; private String reviewComment;
    private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
