package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AiSignal {
    private Long id;
    private String agentId;
    private String sessionId;
    private Long assistantMessageId;
    private String signalType;
    private String sourceType;
    private String severity;
    private Double confidence;
    private String summary;
    private String rationale;
    private String modelId;
    private String status;
    private LocalDateTime createdAt;
}
