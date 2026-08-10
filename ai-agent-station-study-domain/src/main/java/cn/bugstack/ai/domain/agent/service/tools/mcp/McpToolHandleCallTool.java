package cn.bugstack.ai.domain.agent.service.tools.mcp;

import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 以 discover_mcp_tools 签发的会话级 Handle 调用真实 MCP。 */
@Component
public class McpToolHandleCallTool extends AbstractReActTool {

    @Resource
    private McpCallTool mcpCallTool;

    @Tool(name = "call_mcp_tool", description = "使用 discover_mcp_tools 返回的 toolHandle 调用真实 MCP；参数必须是候选 Schema 允许的 JSON 对象。")
    public String callMcpToolByHandle(
            @ToolParam(description = "discover_mcp_tools 返回的会话级 toolHandle") String toolHandle,
            @ToolParam(description = "符合候选 inputSchema 的 JSON 参数对象") String args) {
        ReActToolContext context = ReActToolContextHolder.get();
        String normalized = toolHandle == null ? "" : toolHandle.trim();
        if (context == null) {
            return reject("call_mcp_handle(" + normalized + ")",
                    "MCP_TOOL_HANDLE_REJECTED: 当前会话没有 Agent 上下文");
        }
        ReActToolContext.McpToolHandleBinding binding = context.getMcpToolHandle(normalized);
        if (binding == null) {
            return reject("call_mcp_handle(" + normalized + ")",
                    "MCP_TOOL_HANDLE_REJECTED: 工具 Handle 不属于当前会话");
        }
        if (!java.util.Objects.equals(context.getAgentId(), binding.agentId())
                || !java.util.Objects.equals(context.getSessionId(), binding.sessionId())) {
            context.removeMcpToolHandle(normalized);
            return reject("call_mcp_handle(" + normalized + ")",
                    "MCP_TOOL_HANDLE_REJECTED: 工具 Handle 不属于当前 Agent 或会话");
        }
        if (binding.isExpired(System.currentTimeMillis())) {
            context.removeMcpToolHandle(normalized);
            return reject("call_mcp_handle(" + normalized + ")",
                    "MCP_TOOL_HANDLE_EXPIRED: 工具 Handle 已过期");
        }
        return mcpCallTool.callMcpToolByHandle(normalized, args == null ? "{}" : args);
    }

    private String reject(String toolName, String message) {
        emitObservation(toolName, message);
        return message;
    }
}
