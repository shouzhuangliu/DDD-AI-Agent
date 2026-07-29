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
public class AgentTodoItem {
    private String todoId;
    private String content;
    private String status;
    private String owner;
    private String subagentTaskId;
    private Integer position;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
