package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CaseEvidence {
    private Long id;
    private String caseId;
    private String agentId;
    private String evidenceType;
    private Long evidenceId;
    private String sessionId;
    private Long messageId;
    private String excerpt;
    private LocalDateTime createdAt;
}
