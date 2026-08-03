package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnalysisJob {
    private Long id;
    private String idempotencyKey;
    private String agentId;
    private String sessionId;
    private Long assistantMessageId;
    private String policyVersion;
    private String modelId;
    private String status;
    private Integer attempts;
    private Integer maxAttempts;
    private LocalDateTime leaseUntil;
    private LocalDateTime availableAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
