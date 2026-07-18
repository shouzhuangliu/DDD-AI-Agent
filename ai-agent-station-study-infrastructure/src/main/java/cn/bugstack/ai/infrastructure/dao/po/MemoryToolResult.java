package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MemoryToolResult {
    private Long id; private String agentId; private String sessionId; private Long messageId;
    private String toolName; private String conclusion; private String keyParametersJson;
    private String errorSummary; private LocalDateTime createdAt;
}
