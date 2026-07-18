package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class AiFeedback {
    private Long id;
    private String sessionId;
    private String agentId;
    private Long assistantMessageId;
    private String feedbackType;
    private Integer rating;
    private String message;
    private String correction;
    private String sourceType;
    private String category;
    private String matchedCaseId;
    private Integer resolved;
    private String status;
    private String submittedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
