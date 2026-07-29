package cn.bugstack.ai.domain.agent.service.execute.react;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ReActToolAllowlistPolicy {

    public static final String READ_FILE = "read_file";
    public static final String WRITE_FILE = "write_file";
    public static final String RUN_BASH = "run_bash";
    public static final String CALL_MCP_TOOL = "call_mcp_tool";
    public static final String RETRIEVE_TOOL_CALL = "retrieve_tool_call";
    public static final String QUERY_CASES = "query_cases";
    public static final String QUERY_FEEDBACK = "query_feedback";
    public static final String TASK = "task";
    public static final String DISPATCH_SUBAGENTS = "dispatch_subagents";

    private static final Set<String> KNOWN_TOOLS = Set.of(
            READ_FILE, WRITE_FILE, RUN_BASH, CALL_MCP_TOOL,
            RETRIEVE_TOOL_CALL, QUERY_CASES, QUERY_FEEDBACK, TASK, DISPATCH_SUBAGENTS);

    public List<String> resolve(List<String> boundTools) {
        if (boundTools == null) {
            return List.of();
        }
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String tool : boundTools) {
            if (tool == null || tool.isBlank()) continue;
            String normalized = tool.trim().toLowerCase();
            if (KNOWN_TOOLS.contains(normalized)) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    public static List<ToolOption> options() {
        return List.of(
                new ToolOption(READ_FILE, "文件读取", "读取 Agent 工作目录下的文件；仅排查项目时开启。", "HIGH"),
                new ToolOption(WRITE_FILE, "文件写入", "写入或覆盖文件；仅开发/修复类 Agent 开启。", "CRITICAL"),
                new ToolOption(RUN_BASH, "Bash 执行", "执行白名单命令；风险较高，默认关闭。", "CRITICAL"),
                new ToolOption(CALL_MCP_TOOL, "MCP 调用", "调用 Agent 绑定的 MCP 能力。", "MEDIUM"),
                new ToolOption(RETRIEVE_TOOL_CALL, "取回工具结果", "按工具调用 ID 取回折叠过的完整结果。", "LOW"),
                new ToolOption(QUERY_CASES, "查询 Case", "查询该 Agent 的历史 Case。", "LOW"),
                new ToolOption(QUERY_FEEDBACK, "查询反馈", "查询该 Agent 的用户/运维反馈。", "LOW"),
                new ToolOption(TASK, "Subagent", "将复杂独立任务交给一级 Subagent（串行委派）。", "MEDIUM"),
                new ToolOption(DISPATCH_SUBAGENTS, "并行 Subagent", "一次性提交多个相互独立的子任务，最多并行 3 个。", "MEDIUM")
        );
    }

    public record ToolOption(String toolId, String name, String description, String riskLevel) {
    }
}
