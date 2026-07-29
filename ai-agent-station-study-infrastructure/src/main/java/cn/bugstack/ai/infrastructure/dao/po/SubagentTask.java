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
public class SubagentTask {
    private Long id;
    private String taskId;
    private String executionId;
    private String agentId;
    private String description;
    private String status;
    private String result;
    private String errorMessage;
    private Boolean cancelRequested;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
