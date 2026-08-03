package cn.bugstack.ai.domain.agent.service.tools.mcp;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ReAct 内部工具：调用绑定的 MCP 工具。
 * <p>
 * 采用 Progressive Disclosure 策略——系统提示词只列出 MCP 工具名和描述，
 * 不挂全量 schema（防上下文膨胀），LLM 决定使用哪个 MCP 工具后，
 * 通过此工具传入 mcpId + toolName + args 执行调用。
 * <p>
 * 一个 MCP 服务可能暴露多个工具，所以需要同时指定 mcpId 和 toolName。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Component
public class McpCallTool extends AbstractReActTool {

    /**
     * Validates the model-provided MCP tool name against tools/list output.
     * A null return means the requested tool is valid.
     */
    public static String validateToolName(Set<String> exposedToolNames, String requestedToolName) {
        if (exposedToolNames != null && exposedToolNames.contains(requestedToolName)) {
            return null;
        }
        String available = exposedToolNames == null || exposedToolNames.isEmpty()
                ? "无"
                : exposedToolNames.stream().sorted().collect(Collectors.joining(", "));
        return "MCP 工具不存在: " + requestedToolName + "，可用工具: " + available;
    }

    /**
     * Validates the required fields from the MCP server schema before a request
     * crosses the process boundary. The schema is intentionally accepted as an
     * Object because MCP SDK versions expose different JsonSchema types.
     */
    public static String validateRequiredArguments(Object inputSchema, JSONObject arguments) {
        if (inputSchema == null) return null;
        JSONObject schema;
        try {
            schema = JSON.parseObject(JSON.toJSONString(inputSchema));
        } catch (Exception e) {
            return "MCP schema cannot be read: " + e.getMessage();
        }
        if (schema == null) return null;
        var required = schema.getJSONArray("required");
        if (required == null || required.isEmpty()) return null;
        JSONObject actual = arguments == null ? new JSONObject() : arguments;
        List<String> missing = required.stream()
                .map(String::valueOf)
                .filter(name -> !actual.containsKey(name)
                        || actual.get(name) == null
                        || (actual.get(name) instanceof String value && value.isBlank()))
                .toList();
        return missing.isEmpty() ? null : "MCP missing required argument(s): " + String.join(", ", missing);
    }

    /** Returns a compact schema summary for progressive disclosure in prompts. */
    public static String requiredArgumentsSummary(Object inputSchema) {
        if (inputSchema == null) return "required: none";
        try {
            JSONObject schema = JSON.parseObject(JSON.toJSONString(inputSchema));
            var required = schema == null ? null : schema.getJSONArray("required");
            if (required == null || required.isEmpty()) return "required: none";
            return "required: " + required.stream().map(String::valueOf).collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "required: unavailable";
        }
    }

    @Resource
    private ApplicationContext applicationContext;

    @Tool(description = "调用一个绑定的 MCP 工具。参数 mcpId 为 MCP 工具配置 ID（格式如 mcp_fetch），toolName 为 MCP 服务暴露的具体工具名，args 为 JSON 格式的参数对象。调用前务必确认参数格式正确。")
    public String callMcpTool(@ToolParam(description = "MCP 工具配置 ID，如 mcp_fetch") String mcpId,
                              @ToolParam(description = "MCP 服务暴露的具体工具名，如 fetch") String toolName,
                              @ToolParam(description = "JSON 格式的参数对象，如 {\"url\":\"https://example.com\"}") String args) {
        String toolTag = "call_mcp(" + mcpId + "/" + toolName + ")";
        emitAction(toolTag, "调用 MCP 工具: " + mcpId + "." + toolName);

        if (mcpId == null || toolName == null) {
            String msg = "mcpId 和 toolName 不能为空";
            emitObservation(toolTag, msg);
            return msg;
        }
        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null || context.getBoundMcpIds() == null || !context.getBoundMcpIds().contains(mcpId.trim())) {
            String msg = "MCP 未绑定到当前 Agent: " + mcpId;
            emitObservation(toolTag, msg);
            return msg;
        }

        // 从 Spring 容器取 McpSyncClient bean
        McpSyncClient client;
        try {
            client = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId),
                    McpSyncClient.class);
        } catch (Exception e) {
            String msg = "MCP 客户端未就绪: " + mcpId + "（可能未注册）";
            log.warn(msg);
            emitObservation(toolTag, msg);
            return msg;
        }

        List<McpSchema.Tool> exposedTools;
        try {
            exposedTools = client.listTools().tools();
            Set<String> exposedToolNames = exposedTools.stream()
                    .map(McpSchema.Tool::name)
                    .collect(Collectors.toSet());
            String validationMessage = validateToolName(exposedToolNames, toolName.trim());
            if (validationMessage != null) {
                emitObservation(toolTag, validationMessage);
                return validationMessage;
            }

        } catch (Exception e) {
            String msg = "无法读取 MCP 工具列表: " + e.getMessage();
            log.warn(msg, e);
            emitObservation(toolTag, msg);
            return msg;
        }

        // 解析参数
        McpSchema.CallToolRequest request;
        try {
            JSONObject jsonArgs = (args != null && !args.isBlank())
                    ? JSON.parseObject(args)
                    : new JSONObject();

            McpSchema.Tool exposedTool = exposedTools.stream()
                    .filter(tool -> toolName.trim().equals(tool.name()))
                    .findFirst()
                    .orElse(null);
            String requiredMessage = validateRequiredArguments(
                    exposedTool == null ? null : exposedTool.inputSchema(), jsonArgs);
            if (requiredMessage != null) {
                emitObservation(toolTag, requiredMessage);
                return requiredMessage;
            }
            request = new McpSchema.CallToolRequest(toolName, jsonArgs);
        } catch (Exception e) {
            String msg = "参数解析失败: " + e.getMessage() + " (args=" + args + ")";
            emitObservation(toolTag, msg);
            return msg;
        }

        // 执行工具调用
        try {
            McpSchema.CallToolResult result = client.callTool(request);

            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent text) {
                        sb.append(text.text()).append("\n");
                    }
                }
            }
            String output = sb.toString().trim();
            if (output.isEmpty()) {
                output = "(无输出, isError=" + result.isError() + ")";
            }
            emitObservation(toolTag, output);
            return output;
        } catch (Exception e) {
            String msg = "MCP 调用异常: " + e.getMessage();
            log.error(msg, e);
            emitObservation(toolTag, msg);
            return msg;
        }
    }
}
