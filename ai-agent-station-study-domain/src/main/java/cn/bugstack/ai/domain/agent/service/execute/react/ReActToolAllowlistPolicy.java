package cn.bugstack.ai.domain.agent.service.execute.react;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 运行时只允许配置页明确绑定的工具进入 Agent。 */
@Service
public class ReActToolAllowlistPolicy {
    public static final String READ_FILE = "read_file";
    public static final String WRITE_FILE = "write_file";
    public static final String RUN_BASH = "run_bash";
    public static final String DISCOVER_MCP_TOOLS = "discover_mcp_tools";
    public static final String GET_MCP_TOOL_SCHEMA = "get_mcp_tool_schema";
    public static final String CALL_MCP_TOOL = "call_mcp_tool";
    public static final String RETRIEVE_TOOL_CALL = "retrieve_tool_call";
    public static final String SEARCH_AGENT_MEMORY = "search_agent_memory";
    public static final String GET_AGENT_MEMORY = "get_agent_memory";
    public static final String UPSERT_AGENT_MEMORY = "upsert_agent_memory";
    public static final String RETIRE_AGENT_MEMORY = "retire_agent_memory";
    public static final String QUERY_CASES = "query_cases";
    public static final String QUERY_FEEDBACK = "query_feedback";
    public static final String TASK = "task";
    public static final String DISPATCH_SUBAGENTS = "dispatch_subagents";

    private static final Set<String> KNOWN_TOOLS = Set.of(
            READ_FILE, WRITE_FILE, RUN_BASH, DISCOVER_MCP_TOOLS, GET_MCP_TOOL_SCHEMA, CALL_MCP_TOOL,
            RETRIEVE_TOOL_CALL, SEARCH_AGENT_MEMORY, GET_AGENT_MEMORY, UPSERT_AGENT_MEMORY, RETIRE_AGENT_MEMORY,
            QUERY_CASES, QUERY_FEEDBACK, TASK, DISPATCH_SUBAGENTS);

    public List<String> resolve(List<String> boundTools) {
        if (boundTools == null) return List.of();
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String tool : boundTools) {
            if (tool == null || tool.isBlank()) continue;
            String normalized = tool.trim().toLowerCase();
            if (KNOWN_TOOLS.contains(normalized)) result.add(normalized);
        }
        return new ArrayList<>(result);
    }

    public static List<ToolOption> options() {
        return List.of(
                new ToolOption(READ_FILE, "文件读取", "读取 Agent 工作目录或已绑定 Skill 的文件。", "HIGH"),
                new ToolOption(WRITE_FILE, "文件写入", "在 Agent 工作目录创建或覆盖文本文件。", "CRITICAL"),
                new ToolOption(RUN_BASH, "Bash 执行", "执行白名单内的 shell 命令，默认不建议开启。", "CRITICAL"),
                new ToolOption(DISCOVER_MCP_TOOLS, "MCP 工具发现", "从当前 Agent 已绑定 MCP 中按意图检索工具。", "LOW"),
                new ToolOption(GET_MCP_TOOL_SCHEMA, "读取 MCP 参数规则", "按需读取已发现 MCP 工具的完整参数 Schema。", "LOW"),
                new ToolOption(CALL_MCP_TOOL, "调用 MCP 工具", "通过工具发现返回的句柄调用当前 Agent 已绑定 MCP。", "MEDIUM"),
                new ToolOption(RETRIEVE_TOOL_CALL, "取回工具完整结果", "按工具调用 ID 取回上下文折叠的完整结果。", "LOW"),
                new ToolOption(SEARCH_AGENT_MEMORY, "搜索长期记忆", "检索当前 Agent 的有效业务记忆索引。", "LOW"),
                new ToolOption(GET_AGENT_MEMORY, "读取长期记忆", "按 memoryId 读取当前 Agent 的有效业务记忆正文。", "LOW"),
                new ToolOption(UPSERT_AGENT_MEMORY, "保存长期记忆", "依据当前会话原文创建或更新当前 Agent 的业务记忆。", "MEDIUM"),
                new ToolOption(RETIRE_AGENT_MEMORY, "失效长期记忆", "依据当前会话原文软删除已失效的业务记忆。", "MEDIUM"),
                new ToolOption(QUERY_CASES, "查询 Case", "查询当前 Agent 的历史 Case。", "LOW"),
                new ToolOption(QUERY_FEEDBACK, "查询反馈", "查询当前 Agent 的用户或运维反馈。", "LOW"),
                new ToolOption(TASK, "子 Agent 任务", "将一个复杂独立任务交给一级子 Agent。", "MEDIUM"),
                new ToolOption(DISPATCH_SUBAGENTS, "并行子 Agent", "并行提交多个独立子任务，最多三个。", "MEDIUM")
        );
    }

    public record ToolOption(String toolId, String name, String description, String riskLevel) { }
}
