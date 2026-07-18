package cn.bugstack.ai.domain.agent.service.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史消息值对象（域层 POJO，不依赖 Spring AI）。
 * <p>
 * 只承载 user / assistant 的纯文本消息。
 * tool 调用中间态不进入历史，避免 tool_calls↔tool 配对错乱导致 LLM 400。
 *
 * @author ai-agent-station-study
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HistoryMessage {
    /** user / assistant */
    private String role;
    /** 消息正文 */
    private String content;
}