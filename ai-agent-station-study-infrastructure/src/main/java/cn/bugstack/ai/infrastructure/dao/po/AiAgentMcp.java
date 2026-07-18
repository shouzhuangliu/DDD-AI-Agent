package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentMcp {
    private Long id;
    private String agentId;
    private String mcpId;
    private Integer status;
    private LocalDateTime createTime;
}