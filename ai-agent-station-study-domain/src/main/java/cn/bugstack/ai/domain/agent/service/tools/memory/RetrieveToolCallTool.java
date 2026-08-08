package cn.bugstack.ai.domain.agent.service.tools.memory;

import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.ToolCallExchange;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按工具调用 ID 从当前会话恢复被折叠的原始工具交换记录。
 */
@Slf4j
@Component
public class RetrieveToolCallTool extends AbstractReActTool {

    private static final int MAX_RETURN_CHARS = 20_000;

    @Resource
    private ChatMessageRecorder recorder;

    @Tool(description = "按 tool_call_id 取回当前会话中被折叠的完整工具结果，不会重新执行原工具")
    public String retrieveToolCall(@ToolParam(description = "工具调用 ID，例如 call_abc") String toolCallId) {
        ReActToolContext context = ReActToolContextHolder.get();
        String sessionId = context == null ? null : context.getSessionId();
        return retrieveToolCall(sessionId, toolCallId);
    }

    /** 服务层和测试使用的会话级入口。 */
    public String retrieveToolCall(String sessionId, String toolCallId) {
        String toolName = "retrieve_tool_call";
        emitAction(toolName, "取回工具调用：" + toolCallId);

        if (sessionId == null || sessionId.isBlank()) {
            return observeError(toolName, "ERROR: session_id cannot be blank");
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            return observeError(toolName, "ERROR: tool_call_id cannot be blank");
        }

        try {
            ToolCallExchange exchange = recorder.findToolExchange(sessionId, toolCallId);
            if (exchange == null) {
                return observeError(toolName, "ERROR: no tool call exchange found for " + toolCallId);
            }

            String result = exchange.resultContent() == null ? "" : exchange.resultContent();
            boolean truncated = result.length() > MAX_RETURN_CHARS;
            String bounded = truncated ? result.substring(0, MAX_RETURN_CHARS) : result;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("toolCallId", exchange.toolCallId());
            response.put("source", "archive");
            response.put("tool", Map.of(
                    "name", exchange.toolName() == null ? "" : exchange.toolName(),
                    "arguments", exchange.toolArguments() == null ? "{}" : exchange.toolArguments()));
            response.put("result", Map.of(
                    "content", bounded,
                    "truncated", truncated,
                    "originalChars", result.length()));
            emitObservation(toolName, "已取回工具调用：" + toolCallId);
            return JSON.toJSONString(response);
        } catch (Exception exception) {
            String message = "ERROR: retrieve failed: " + exception.getMessage();
            log.error(message, exception);
            return observeError(toolName, message);
        }
    }

    private String observeError(String toolName, String message) {
        emitObservation(toolName, message);
        return message;
    }
}
