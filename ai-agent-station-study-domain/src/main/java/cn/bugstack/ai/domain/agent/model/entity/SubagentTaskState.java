package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubagentTaskState {
    private String taskId;
    private String executionId;
    private String agentId;
    private String description;
    private String status;
    private String result;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    /** 取消标志：cancel(taskId) 置 true，执行体在工具调用间隙检查，置 CANCELLED 终态。 */
    private volatile boolean cancelRequested;
}
