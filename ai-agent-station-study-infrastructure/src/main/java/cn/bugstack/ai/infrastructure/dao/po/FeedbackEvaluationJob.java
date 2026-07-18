package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackEvaluationJob {
    private Long id;
    private String idempotencyKey;
    private String agentId;
    private Long feedbackId;
    private String policyVersion;
    private String status;
    private Integer attempts;
    private Integer maxAttempts;
    private LocalDateTime leaseUntil;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
