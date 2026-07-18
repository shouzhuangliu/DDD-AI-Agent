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
public class AiAgentSkill {
    private Long id;
    private String agentId;
    private String skillId;
    private Integer status;
    private LocalDateTime createTime;
}