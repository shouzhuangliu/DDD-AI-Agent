package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryEvidence {
    private Long id; private String memoryOwnerType; private String memoryOwnerId; private String agentId;
    private String sourceType; private String sourceId; private String sessionId; private Long messageId;
    private String toolCallId; private String evidenceQuote; private String contentHash; private LocalDateTime createdAt;
}
