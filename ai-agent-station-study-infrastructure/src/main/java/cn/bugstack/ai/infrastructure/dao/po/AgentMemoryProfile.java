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
public class AgentMemoryProfile {
    private Long id;
    private String agentId;
    private Integer version;
    private String profileJson;
    private String sourceCaseIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
