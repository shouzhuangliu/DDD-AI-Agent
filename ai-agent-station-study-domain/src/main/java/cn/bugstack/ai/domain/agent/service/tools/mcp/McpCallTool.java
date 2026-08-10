package cn.bugstack.ai.domain.agent.service.tools.mcp;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** ReAct 内部工具：调用当前 Agent 已绑定并通过 tools/list 校验的 MCP 工具。 */
@Slf4j
@Component
public class McpCallTool extends AbstractReActTool {

    /** Validates a model-provided MCP tool name against the live tools/list result. */
    public static String validateToolName(Set<String> exposedToolNames, String requestedToolName) {
        if (exposedToolNames != null && exposedToolNames.contains(requestedToolName)) return null;
        String available = exposedToolNames == null || exposedToolNames.isEmpty()
                ? "无" : exposedToolNames.stream().sorted().collect(Collectors.joining(", "));
        return "MCP 工具不存在: " + requestedToolName + "，可用工具: " + available;
    }

    /** Validates required fields from an MCP JSON schema before crossing the process boundary. */
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

    /** 判断用户是否明确要求查询今天/今日的反馈。 */
    public static boolean isTodayFeedbackQuery(String message) {
        if (message == null || message.isBlank()) return false;
        String text = message.trim().toLowerCase(java.util.Locale.ROOT);
        boolean feedback = text.contains("反馈") || text.contains("feedback");
        boolean today = text.contains("今日") || text.contains("今天") || text.contains("当天") || text.contains("当日");
        return feedback && today;
    }

    /** 返回专门查询今日反馈的工具名，不存在时不允许降级为模糊搜索。 */
    public static String preferredTodayFeedbackTool(Set<String> exposedToolNames) {
        return exposedToolNames != null && exposedToolNames.contains("get_today_feedback")
                ? "get_today_feedback" : "";
    }

    @Resource
    private ApplicationContext applicationContext;

    @Tool(description = "调用一个绑定的 MCP 工具。今日反馈查询必须使用 get_today_feedback。")
    public String callMcpTool(
            @ToolParam(description = "MCP 工具配置 ID") String mcpId,
            @ToolParam(description = "MCP 服务暴露的具体工具名") String toolName,
            @ToolParam(description = "JSON 格式的参数对象") String args) {
        String initialTag = "call_mcp(" + mcpId + "/" + toolName + ")";
        if (mcpId == null || toolName == null) {
            String message = "mcpId 和 toolName 不能为空";
            emitObservation(initialTag, message);
            return message;
        }

        ReActToolContext context = ReActToolContextHolder.get();
        List<String> boundMcpIds = context == null || context.getBoundMcpIds() == null
                ? List.of() : context.getBoundMcpIds();
        if (boundMcpIds.isEmpty()) {
            String message = "当前 Agent 未绑定 MCP，无法查询今日反馈";
            emitObservation(initialTag, message);
            return message;
        }

        String effectiveMcpId = mcpId.trim();
        String effectiveToolName = toolName.trim();
        String effectiveArgs = args;
        boolean todayQuery = isTodayFeedbackQuery(context == null ? null : context.getUserMessage());
        McpTarget target;
        try {
            if (todayQuery) {
                target = findTodayFeedbackTarget(boundMcpIds);
                if (target == null) {
                    String message = "今日反馈查询失败：当前 Agent 绑定的 MCP 未提供 get_today_feedback。"
                            + "实际绑定工具为 " + describeBoundTools(boundMcpIds)
                            + "；请绑定本地 inventory_feedback_mcp.py 对应的 MCP 版本。";
                    emitObservation("call_mcp(today_feedback)", message);
                    return message;
                }
                effectiveMcpId = target.mcpId();
                effectiveToolName = "get_today_feedback";
                effectiveArgs = todayFeedbackArgs(args);
            } else {
                if (!boundMcpIds.contains(effectiveMcpId)) {
                    String message = "MCP 未绑定到当前 Agent: " + effectiveMcpId;
                    emitObservation(initialTag, message);
                    return message;
                }
                target = loadTarget(effectiveMcpId);
            }
        } catch (Exception e) {
            String message = "无法读取 MCP 工具列表: " + safeMessage(e);
            log.warn(message, e);
            emitObservation(initialTag, message);
            return message;
        }

        String toolTag = "call_mcp(" + effectiveMcpId + "/" + effectiveToolName + ")";
        Set<String> exposedToolNames = target.tools().stream()
                .map(McpSchema.Tool::name).collect(Collectors.toSet());
        String validationMessage = validateToolName(exposedToolNames, effectiveToolName);
        if (validationMessage != null) {
            emitObservation(toolTag, validationMessage);
            return validationMessage;
        }

        ReActToolContext currentContext = ReActToolContextHolder.get();
        McpSchema.Tool loadedSchema = currentContext == null
                ? null : currentContext.getMcpToolSchema(effectiveMcpId, effectiveToolName);
        if (loadedSchema == null) {
            String message = "MCP_SCHEMA_REQUIRED: 调用 " + effectiveToolName
                    + " 前必须先调用 get_mcp_tool_schema 获取完整参数 Schema";
            emitObservation(toolTag, message);
            return message;
        }

        emitAction(toolTag, "调用 MCP 工具: " + effectiveMcpId + "." + effectiveToolName);

        try {
            JSONObject jsonArgs = effectiveArgs != null && !effectiveArgs.isBlank()
                    ? JSON.parseObject(effectiveArgs) : new JSONObject();
            String requiredMessage = validateRequiredArguments(
                    loadedSchema.inputSchema(), jsonArgs);
            if (requiredMessage != null) {
                emitObservation(toolTag, requiredMessage);
                return requiredMessage;
            }
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(effectiveToolName, jsonArgs);
            McpSchema.CallToolResult result = target.client().callTool(request);
            StringBuilder output = new StringBuilder();
            if (result != null && result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent text) output.append(text.text()).append('\n');
                }
            }
            String value = output.toString().trim();
            if (value.isEmpty()) value = "MCP 返回空内容（isError=" + (result != null && result.isError()) + ")";
            emitObservation(toolTag, value);
            return value;
        } catch (Exception e) {
            String message = "MCP 调用异常: " + safeMessage(e);
            log.error(message, e);
            emitObservation(toolTag, message);
            return message;
        }
    }

    private McpTarget findTodayFeedbackTarget(List<String> boundMcpIds) {
        for (String boundMcpId : boundMcpIds) {
            try {
                McpTarget target = loadTarget(boundMcpId);
                Set<String> names = target.tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
                if (!preferredTodayFeedbackTool(names).isBlank()) return target;
            } catch (Exception ignored) {
                log.debug("今日反馈 MCP 探测失败: {}", boundMcpId);
            }
        }
        return null;
    }

    private McpTarget loadTarget(String mcpId) {
        McpSyncClient client = applicationContext.getBean(
                AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId), McpSyncClient.class);
        List<McpSchema.Tool> tools = client.listTools().tools();
        return new McpTarget(mcpId, client, tools == null ? List.of() : tools);
    }

    private String describeBoundTools(List<String> boundMcpIds) {
        return boundMcpIds.stream().map(id -> {
            try {
                McpTarget target = loadTarget(id);
                String names = target.tools().stream().map(McpSchema.Tool::name)
                        .sorted().collect(Collectors.joining(", "));
                return id + "[" + (names.isBlank() ? "无工具" : names) + "]";
            } catch (Exception e) {
                return id + "[客户端未就绪]";
            }
        }).collect(Collectors.joining("；"));
    }

    private String todayFeedbackArgs(String args) {
        JSONObject normalized = new JSONObject();
        if (args != null && !args.isBlank()) {
            try {
                JSONObject requested = JSON.parseObject(args);
                if (requested != null) {
                    if (requested.getString("source") != null) normalized.put("source", requested.getString("source"));
                    if (requested.getString("service") != null) normalized.put("service", requested.getString("service"));
                }
            } catch (Exception ignored) {
                // 今日反馈查询由服务端补齐参数，模型传入的非 JSON 参数不应阻断查询。
            }
        }
        normalized.put("limit", 50);
        return JSON.toJSONString(normalized);
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record McpTarget(String mcpId, McpSyncClient client, List<McpSchema.Tool> tools) {
    }
}
