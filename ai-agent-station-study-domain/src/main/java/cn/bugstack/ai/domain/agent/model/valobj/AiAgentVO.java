package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI Agent 视图对象。
 * 用于前端展示与执行时读取灵魂/模型/工作目录等配置。
 * @author ai-agent-station-study
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentVO {
    private Long id;
    private String agentId;
    private String agentName;
    private String description;
    /** 灵魂：system prompt */
    private String systemPrompt;
    /** 绑定的模型 id（对应 ai_client_model 的 bean 后缀） */
    private String modelId;
    /** 工具沙箱工作目录（空则用全局默认） */
    private String workDir;
    /** 渠道/模式：auto | react */
    private String channel;
    private Integer status;
}