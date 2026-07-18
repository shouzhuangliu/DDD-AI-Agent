package cn.bugstack.ai.infrastructure.dao.po;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MemoryState {
    private Long id; private String agentId; private String sessionId; private Integer version;
    private String goalsJson; private String constraintsJson; private String entitiesJson;
    private String pendingJson; private String completedJson; private LocalDateTime createdAt;
}
