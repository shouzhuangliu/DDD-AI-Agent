package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryChangeLog {
    private Long id; private String changeId; private String agentId; private String memoryId; private Integer memoryVersion;
    private String operation; private String reason; private String sourceType; private String sourceId; private LocalDateTime createdAt;
}
