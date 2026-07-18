package cn.bugstack.ai.domain.agent.service.tools.memory;

import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 取回工具：按 tool_call_id 从 DB 捞回被折叠的消息原件。
 * <p>
 * 折叠只发生在「发给 LLM 的副本」上，DB 永远是完整原件。
 * LLM 在上下文中看到折叠指针后可调用此工具取回原文。
 */
@Slf4j
@Component
public class RetrieveToolCallTool extends AbstractReActTool {

    @Resource
    private ChatMessageRecorder recorder;

    @Tool(description = "按 tool_call_id 取回被折叠的完整消息原文。参数 toolCallId 为工具调用 ID（如 call_abc）。当上下文中的消息被折叠或标记为 retrieve 时，调用此工具获取完整内容。")
    public String retrieveToolCall(@ToolParam(description = "工具调用 ID，如 call_abc") String toolCallId) {
        String toolName = "retrieve_tool_call";
        emitAction(toolName, "取回消息: " + toolCallId);

        if (toolCallId == null || toolCallId.isBlank()) {
            String msg = "ERROR: tool_call_id 不能为空";
            emitObservation(toolName, msg);
            return msg;
        }

        try {
            String result = recorder.findByToolCallId(toolCallId);
            if (result != null) {
                emitObservation(toolName, "已取回消息: " + toolCallId);
                return result;
            }
            String msg = "ERROR: no tool call exchange found for " + toolCallId;
            emitObservation(toolName, msg);
            return msg;
        } catch (Exception e) {
            String msg = "ERROR: retrieve failed: " + e.getMessage();
            log.error(msg, e);
            emitObservation(toolName, msg);
            return msg;
        }
    }
}