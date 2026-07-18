package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CaseScoreSnapshot {
    private Long id;
    private String caseId;
    private String agentId;
    private Double totalScore;
    private Double severityScore;
    private Double negativeFeedbackScore;
    private Double frequencyScore;
    private Double importanceScore;
    private Double recencyScore;
    private Double unresolvedAgeScore;
    private Double confidenceScore;
    private Integer priorityFloorApplied;
    private String rationale;
    private LocalDateTime createdAt;
}
