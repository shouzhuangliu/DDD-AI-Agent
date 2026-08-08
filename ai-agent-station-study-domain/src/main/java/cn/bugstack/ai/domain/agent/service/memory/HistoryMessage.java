package cn.bugstack.ai.domain.agent.service.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史消息值对象（领域层 POJO，不依赖 Spring AI）。
 * 除了 user / assistant 文本，也携带 assistant.tool_calls 和 tool 回执元数据，
 * 供推理前的折叠管线还原合法的工具消息配对。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HistoryMessage {
    /** user / assistant / tool */
    private String role;
    /** 消息正文 */
    private String content;
    /** 工具调用 ID；assistant/tool 配对的主键 */
    private String toolCallId;
    /** 工具名称 */
    private String toolName;
    /** assistant 发起工具调用时的原始参数 JSON */
    private String toolArguments;
    /** assistant.tool_calls 原始 JSON 数组 */
    private String toolCallsJson;
}
