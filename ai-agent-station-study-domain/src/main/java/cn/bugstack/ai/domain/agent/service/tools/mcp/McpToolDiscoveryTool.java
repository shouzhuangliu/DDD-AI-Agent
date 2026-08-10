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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 按用户意图从当前 Agent 绑定的 MCP 中发现少量工具，并返回可直接调用的完整 Schema。 */
@Slf4j
@Component
public class McpToolDiscoveryTool extends AbstractReActTool {

    private static final int DEFAULT_LIMIT = 3;
    private static final long HANDLE_TTL_MILLIS = 10 * 60 * 1000L;

    private static final Map<String, List<String>> BUSINESS_ALIASES = Map.of(
            "反馈", List.of("feedback", "反馈"),
            "库存", List.of("inventory", "stock", "库存"),
            "今日", List.of("today", "daily", "今日", "当日"),
            "详情", List.of("detail", "详情", "明细"),
            "分诊", List.of("triage", "triaged", "分诊")
    );

    @Resource
    private ApplicationContext applicationContext;

    @Tool(name = "discover_mcp_tools", description = "按用户意图从当前 Agent 绑定的 MCP 中检索最多三个工具，并返回候选工具的完整 inputSchema 和会话级 toolHandle；没有匹配时禁止猜测工具名。")
    public String discoverMcpTools(
            @ToolParam(description = "用户想完成的业务意图，例如查询今日库存反馈") String query,
            @ToolParam(required = false, description = "可选的 MCP 配置 ID；不传时在当前 Agent 已绑定的 MCP 中检索") String mcpId,
            @ToolParam(required = false, description = "返回候选数量，最大 3，默认 3") Integer limit) {
        ReActToolContext context = ReActToolContextHolder.get();
        String normalizedQuery = normalize(query);
        String normalizedMcpId = normalize(mcpId);
        int requestedLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(DEFAULT_LIMIT, limit));
        String traceTag = "discover_mcp(" + (normalizedMcpId.isBlank() ? "bound" : normalizedMcpId) + ")";

        if (context == null) return reject(traceTag, "MCP_TOOL_DISCOVERY_FAILED: 当前会话没有 Agent 上下文");
        if (normalizedQuery.isBlank()) return reject(traceTag, "MCP_TOOL_NOT_FOUND: 查询意图不能为空");

        List<String> boundMcpIds = context.getBoundMcpIds() == null
                ? List.of() : context.getBoundMcpIds().stream().map(this::normalize).filter(id -> !id.isBlank()).distinct().toList();
        if (!normalizedMcpId.isBlank() && !boundMcpIds.contains(normalizedMcpId)) {
            return reject(traceTag, "MCP_NOT_BOUND: 当前 Agent 未绑定 MCP " + normalizedMcpId);
        }
        List<String> searchMcpIds = normalizedMcpId.isBlank()
                ? boundMcpIds : List.of(normalizedMcpId);
        if (searchMcpIds.isEmpty()) return reject(traceTag, "MCP_NOT_BOUND: 当前 Agent 未绑定任何 MCP");

        emitAction(traceTag, "按意图检索绑定 MCP 工具：" + normalizedQuery);
        List<ScoredTool> scoredTools = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String boundId : searchMcpIds) {
            try {
                McpSyncClient client = applicationContext.getBean(
                        AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(boundId), McpSyncClient.class);
                McpSchema.ListToolsResult listResult = client.listTools();
                List<McpSchema.Tool> tools = listResult == null || listResult.tools() == null
                        ? List.of() : listResult.tools();
                for (McpSchema.Tool tool : tools) {
                    if (tool == null || normalize(tool.name()).isBlank()) continue;
                    int score = score(normalizedQuery, tool);
                    if (score > 0) scoredTools.add(new ScoredTool(boundId, tool, score));
                }
            } catch (Exception e) {
                failures.add(boundId + ":" + safeMessage(e));
                log.warn("MCP 工具发现失败: {}", boundId, e);
            }
        }

        scoredTools.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                .thenComparing(ScoredTool::mcpId)
                .thenComparing(item -> normalize(item.tool().name())));
        List<ScoredTool> selected = scoredTools.stream().limit(requestedLimit).toList();
        if (selected.isEmpty()) {
            String message = failures.isEmpty()
                    ? "MCP_TOOL_NOT_FOUND: 未找到与意图匹配的 MCP 工具"
                    : "MCP_TOOL_DISCOVERY_FAILED: MCP 工具目录读取失败 " + String.join("; ", failures);
            return reject(traceTag, message);
        }

        List<Map<String, Object>> candidates = selected.stream()
                .map(item -> candidate(context, item))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", normalizedQuery);
        result.put("limit", requestedLimit);
        result.put("count", candidates.size());
        result.put("candidates", candidates);
        if (!failures.isEmpty()) result.put("partialFailures", failures);
        String payload = JSON.toJSONString(result);
        emitObservation(traceTag, "发现 " + candidates.size() + " 个 MCP 候选工具，已返回完整 Schema");
        return payload;
    }

    private Map<String, Object> candidate(ReActToolContext context, ScoredTool scoredTool) {
        McpSchema.Tool tool = scoredTool.tool();
        String mcpId = scoredTool.mcpId();
        String toolName = normalize(tool.name());
        String schemaHash = schemaHash(tool);
        String handle = "mcp-tool-" + UUID.randomUUID();
        long expiresAt = System.currentTimeMillis() + HANDLE_TTL_MILLIS;
        context.rememberMcpToolSchema(mcpId, toolName, tool);
        context.rememberMcpToolHandle(handle, new ReActToolContext.McpToolHandleBinding(
                handle, context.getAgentId(), context.getSessionId(), mcpId, toolName,
                schemaHash, expiresAt, tool));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolHandle", handle);
        result.put("mcpId", mcpId);
        result.put("toolName", toolName);
        result.put("description", tool.description() == null ? "" : tool.description());
        result.put("inputSchema", tool.inputSchema());
        result.put("schemaHash", schemaHash);
        result.put("expiresAtEpochMillis", expiresAt);
        return result;
    }

    private int score(String query, McpSchema.Tool tool) {
        String name = normalize(tool.name()).toLowerCase(Locale.ROOT);
        String description = normalize(tool.description()).toLowerCase(Locale.ROOT);
        String text = name + " " + description;
        int score = 0;
        if (text.contains(query.toLowerCase(Locale.ROOT))) score += 100;
        for (Map.Entry<String, List<String>> alias : BUSINESS_ALIASES.entrySet()) {
            if (!query.contains(alias.getKey())) continue;
            for (String term : alias.getValue()) {
                if (text.contains(term.toLowerCase(Locale.ROOT))) score += 10;
            }
        }
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s,，。！？、/]+")) {
            if (token.length() >= 2 && text.contains(token)) score += 3;
        }
        return score;
    }

    private String schemaHash(McpSchema.Tool tool) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", tool.name());
        material.put("description", tool.description());
        material.put("inputSchema", tool.inputSchema());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJSONString(material).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder("sha256:");
            for (byte value : digest) hash.append(String.format("%02x", value));
            return hash.toString();
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String reject(String traceTag, String message) {
        emitObservation(traceTag, message);
        return message;
    }

    private record ScoredTool(String mcpId, McpSchema.Tool tool, int score) {
    }
}
