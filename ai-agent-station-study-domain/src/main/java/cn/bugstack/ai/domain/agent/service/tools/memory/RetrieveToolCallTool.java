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

    @Tool(description = "分页取回工具结果；当 retrieve_tool_call 返回 hasMore=true 时使用")
    public String retrieveToolCallPage(
            @ToolParam(description = "工具调用 ID") String toolCallId,
            @ToolParam(description = "起始字符偏移，默认 0") Integer offset,
            @ToolParam(description = "单页字符数，最大 20000") Integer limit) {
        ReActToolContext context = ReActToolContextHolder.get();
        return retrieveToolCallPage(context == null ? null : context.getSessionId(), toolCallId,
                offset == null ? 0 : offset, limit == null ? MAX_RETURN_CHARS : limit);
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
            response.put("freshness", "HISTORICAL");
            response.put("reexecuteForLatest", true);
            response.put("tool", Map.of(
                    "name", exchange.toolName() == null ? "" : exchange.toolName(),
                    "arguments", exchange.toolArguments() == null ? "{}" : exchange.toolArguments()));
            response.put("result", Map.of(
                    "content", bounded,
                    "truncated", truncated,
                    "originalChars", result.length(),
                    "offset", 0,
                    "hasMore", truncated,
                    "nextOffset", truncated ? MAX_RETURN_CHARS : -1));
            emitObservation(toolName, "已取回工具调用：" + toolCallId);
            return JSON.toJSONString(response);
        } catch (Exception exception) {
            String message = "ERROR: retrieve failed: " + exception.getMessage();
            log.error(message, exception);
            return observeError(toolName, message);
        }
    }

    public String retrieveToolCallPage(String sessionId, String toolCallId, int offset, int limit) {
        String toolName = "retrieve_tool_call";
        if (sessionId == null || sessionId.isBlank()) return observeError(toolName, "ERROR: session_id cannot be blank");
        if (toolCallId == null || toolCallId.isBlank()) return observeError(toolName, "ERROR: tool_call_id cannot be blank");
        try {
            ToolCallExchange exchange = recorder.findToolExchange(sessionId, toolCallId);
            if (exchange == null) return observeError(toolName, "ERROR: no tool call exchange found for " + toolCallId);
            String result = exchange.resultContent() == null ? "" : exchange.resultContent();
            int safeOffset = Math.max(0, Math.min(offset, result.length()));
            int safeLimit = Math.max(1, Math.min(limit, MAX_RETURN_CHARS));
            int end = Math.min(result.length(), safeOffset + safeLimit);
            boolean hasMore = end < result.length();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("toolCallId", exchange.toolCallId());
            response.put("source", "archive");
            response.put("freshness", "HISTORICAL");
            response.put("reexecuteForLatest", true);
            response.put("tool", Map.of("name", exchange.toolName() == null ? "" : exchange.toolName(),
                    "arguments", exchange.toolArguments() == null ? "{}" : exchange.toolArguments()));
            response.put("result", Map.of("content", result.substring(safeOffset, end),
                    "truncated", hasMore || safeOffset > 0,
                    "originalChars", result.length(), "offset", safeOffset,
                    "hasMore", hasMore, "nextOffset", hasMore ? end : -1));
            return JSON.toJSONString(response);
        } catch (Exception exception) {
            return observeError(toolName, "ERROR: retrieve failed: " + exception.getMessage());
        }
    }

    private String observeError(String toolName, String message) {
        emitObservation(toolName, message);
        return message;
    }
}
