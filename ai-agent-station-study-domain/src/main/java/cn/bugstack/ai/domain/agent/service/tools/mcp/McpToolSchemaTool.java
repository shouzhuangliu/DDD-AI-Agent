package cn.bugstack.ai.domain.agent.service.tools.mcp;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 渐进式披露工具：只读取当前 Agent 已绑定 MCP 的指定工具 Schema，不执行业务调用。
 */
@Slf4j
@Component
public class McpToolSchemaTool extends AbstractReActTool {

    @Resource
    private ApplicationContext applicationContext;

    @Tool(description = "按需读取一个已绑定 MCP 工具的完整参数 Schema；读取 Schema 不执行业务操作。")
    public String getMcpToolSchema(
            @ToolParam(description = "MCP 工具配置 ID") String mcpId,
            @ToolParam(description = "MCP 服务暴露的具体工具名") String toolName) {
        String normalizedMcpId = normalize(mcpId);
        String normalizedToolName = normalize(toolName);
        String traceTag = "mcp_schema(" + normalizedMcpId + "/" + normalizedToolName + ")";

        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null) {
            return reject(traceTag, "MCP_SCHEMA_REJECTED: 当前会话没有 Agent 上下文");
        }
        if (normalizedMcpId.isBlank() || normalizedToolName.isBlank()) {
            return reject(traceTag, "MCP_SCHEMA_REJECTED: mcpId 和 toolName 不能为空");
        }
        List<String> boundMcpIds = context.getBoundMcpIds() == null
                ? List.of() : context.getBoundMcpIds();
        if (!boundMcpIds.contains(normalizedMcpId)) {
            return reject(traceTag, "MCP 未绑定: 当前 Agent 未绑定 MCP " + normalizedMcpId);
        }

        if (context.hasMcpToolSchema(normalizedMcpId, normalizedToolName)) {
            String cached = schemaJson(normalizedMcpId,
                    context.getMcpToolSchema(normalizedMcpId, normalizedToolName));
            emitObservation(traceTag, "已使用本次会话缓存的 MCP 工具 Schema");
            return cached;
        }

        try {
            emitAction(traceTag, "读取 MCP 工具完整 Schema: " + normalizedMcpId + "." + normalizedToolName);
            McpSyncClient client = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(normalizedMcpId), McpSyncClient.class);
            McpSchema.ListToolsResult listResult = client.listTools();
            List<McpSchema.Tool> tools = listResult == null || listResult.tools() == null
                    ? List.of() : listResult.tools();
            McpSchema.Tool tool = tools.stream()
                    .filter(item -> normalizedToolName.equals(item.name()))
                    .findFirst()
                    .orElse(null);
            if (tool == null) {
                return reject(traceTag, "MCP_TOOL_NOT_FOUND: MCP 未暴露工具 " + normalizedToolName);
            }
            context.rememberMcpToolSchema(normalizedMcpId, normalizedToolName, tool);
            String result = schemaJson(normalizedMcpId, tool);
            emitObservation(traceTag, "MCP 工具 Schema 已加载，可继续调用真实工具");
            return result;
        } catch (Exception e) {
            String message = "MCP_SCHEMA_UNAVAILABLE: 无法读取 MCP 工具 Schema: " + safeMessage(e);
            log.warn(message, e);
            emitObservation(traceTag, message);
            return message;
        }
    }

    private String schemaJson(String mcpId, McpSchema.Tool tool) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mcpId", mcpId);
        result.put("name", tool.name());
        result.put("description", tool.description() == null ? "" : tool.description());
        result.put("inputSchema", tool.inputSchema());
        return JSON.toJSONString(result);
    }

    private String reject(String traceTag, String message) {
        emitObservation(traceTag, message);
        return message;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
