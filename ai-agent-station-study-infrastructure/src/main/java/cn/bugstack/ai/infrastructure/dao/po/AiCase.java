package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class AiCase {
    private Long id;
    private String caseId;
    private String agentId;
    private String title;
    private String summary;
    private String caseType;
    private String severity;
    private Integer frequency;
    private Integer affectedSessions;
    private Double importanceScore;
    private Double confidence;
    private Double totalScore;
    private String status;
    private String skillId;
    private String sourceModel;
    private String extractionReason;
    private String owner;
    private String resolution;
    private String mergedToCaseId;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
