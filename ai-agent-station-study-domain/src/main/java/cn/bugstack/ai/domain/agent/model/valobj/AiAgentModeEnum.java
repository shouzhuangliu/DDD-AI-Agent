package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI Agent 执行模式枚举
 * <p>
 * 通过请求体的 mode 字段选择执行策略：
 * - AUTO  : 多步分析-执行-监督-总结链路（Todo 模式，含意图分流）
 * - REACT : 模型自主推理 + 工具调用循环（ReAct 智能体模式）
 *
 * @author ai-agent-station-study
 */
@Getter
@AllArgsConstructor
public enum AiAgentModeEnum {

    AUTO("auto", "Auto 多步链路"),
    REACT("react", "ReAct 工具循环");

    private final String code;
    private final String info;

    public static AiAgentModeEnum getByCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return AUTO;
        }
        String c = code.trim().toLowerCase();
        for (AiAgentModeEnum e : values()) {
            if (e.code.equals(c)) {
                return e;
            }
        }
        return AUTO;
    }
}
