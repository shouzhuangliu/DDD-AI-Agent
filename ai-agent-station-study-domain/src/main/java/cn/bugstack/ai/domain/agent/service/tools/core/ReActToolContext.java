package cn.bugstack.ai.domain.agent.service.tools.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * ReAct 工具执行上下文。
 * <p>
 * 每次请求一个实例，由 {@link ReActToolContextHolder} 以 ThreadLocal 暴露给工具方法，
 * 使内部 @Tool 方法能拿到 SSE emitter 推送 action/observation，以及沙箱工作目录。
 *
 * @author ai-agent-station-study
 */
@Data
@Builder
@AllArgsConstructor
public class ReActToolContext {

    /** 会话ID */
    private String sessionId;

    /** 当前轮用户原始消息，用于对语义明确的 MCP 查询做服务端校验。 */
    private String userMessage;

    /** Agent ID */
    private String agentId;

    /** SSE 输出流 */
    private ResponseBodyEmitter emitter;

    /** 工具沙箱根目录（读写文件/命令执行都限制在此目录内） */
    private Path workDir;

    /** 当前 Agent 已绑定的 Skills */
    private List<String> boundSkillIds;

    /** 当前 Agent 已绑定的 MCP */
    private List<String> boundMcpIds;

    /** 本次执行授权的工具白名单（工具 id）。子 Agent 据此镜像父工具集（剔除 task/dispatch_subagents）。 */
    private List<String> allowedTools;

    /** Agent 配置页显式勾选的工具。隐式工具（如 Skill 自动带 read_file）不在这里。 */
    private List<String> explicitToolIds;

    /** 本次执行的持久化状态 ID。 */
    private String executionId;

    private String modelId;

    /** 本次执行允许的最大工具步数。 */
    @Builder.Default
    private int maxSteps = 30;

    /** 兼容旧配置的安全字段；业务频率不由固定 10 次决定，实际由滑动窗口策略控制。 */
    @Builder.Default
    private int maxToolCalls = 30;

    @Builder.Default
    private int maxModelRounds = 8;

    @Builder.Default
    private AtomicInteger currentStep = new AtomicInteger(0);

    @Builder.Default
    private Deque<String> recentToolCalls = new ArrayDeque<>();

    @Builder.Default
    private Map<String, Integer> repeatedToolCalls = new HashMap<>();

    /** 重复调用判定窗口，按最近 N 次工具请求计算。 */
    @Builder.Default
    private int repeatWindowSize = 8;

    /** 同一工具在窗口内允许的不同参数调用次数。 */
    @Builder.Default
    private int sameToolWindowLimit = 4;

    private BooleanSupplier cancellationCheck;

    /**
     * 为一次工具动作申请预算。返回负数表示已耗尽预算。
     * AtomicInteger 保证同一请求内并发工具回调不会重复使用步数。
     */
    public int consumeStep() {
        while (true) {
            int current = currentStep.get();
            // maxSteps 是最终安全熔断；工具频率由 admitToolCall 的滑动窗口负责。
            if (current >= maxSteps) return -1;
            if (currentStep.compareAndSet(current, current + 1)) return current + 1;
        }
    }

    public boolean isCancellationRequested() {
        return cancellationCheck != null && cancellationCheck.getAsBoolean();
    }

    /**
     * 兼容旧调用方的重复检测：同一工具和参数在滑动窗口内第二次出现时返回 true。
     */
    public synchronized boolean repeatedCallExceeded(String toolName, String description) {
        String key = callKey(toolName, description);
        boolean duplicate = repeatedToolCalls.containsKey(key);
        rememberCall(key);
        return duplicate;
    }

    public ToolCallDecision admitToolCall(String toolName, String arguments, boolean callbackRegistered) {
        return admitToolCall(toolName, arguments, callbackRegistered, true);
    }

    /**
     * 服务端工具准入：先校验绑定权限和当前用户意图，再校验滑动窗口内的重复/超频调用。
     */
    public synchronized ToolCallDecision admitToolCall(String toolName, String arguments,
                                                        boolean callbackRegistered, boolean authorized) {
        String normalizedTool = toolName == null ? "" : toolName.trim();
        if (!authorized) {
            return ToolCallDecision.reject("UNAUTHORIZED_TOOL",
                    "工具调用已拦截：当前 Agent 未授权工具“" + normalizedTool + "”，请在 Agent 配置中绑定后再调用。");
        }
        if (normalizedTool.isBlank() || !callbackRegistered) {
            return ToolCallDecision.reject("UNKNOWN_TOOL",
                    "工具调用已拦截：当前运行时没有可用工具“" + normalizedTool + "”，请先查看 Agent 的已绑定工具。");
        }
        if (!intentAllows(normalizedTool)) {
            return ToolCallDecision.reject("INTENT_NOT_ALLOWED",
                    "工具调用已拦截：当前消息未授权执行“" + normalizedTool + "”，请明确说明要排查代码、读取文件或运行命令。");
        }

        String key = callKey(normalizedTool, arguments);
        if (repeatedToolCalls.containsKey(key)) {
            return ToolCallDecision.reject("DUPLICATE_TOOL_CALL",
                    "工具调用已拦截：窗口内已调用过“" + normalizedTool + "”且参数相同，请直接使用上一次结果。");
        }
        long sameToolCount = recentToolCalls.stream()
                .filter(item -> item.startsWith(normalizedTool + "\n"))
                .count();
        if (sameToolCount >= Math.max(1, sameToolWindowLimit)) {
            return ToolCallDecision.reject("TOOL_FREQUENCY",
                    "工具调用已拦截：“" + normalizedTool + "”在最近 " + Math.max(1, repeatWindowSize)
                            + " 次窗口内调用过于频繁，请改变查询条件或补充业务信息。");
        }
        rememberCall(key);
        return ToolCallDecision.allow(normalizedTool);
    }

    private void rememberCall(String key) {
        recentToolCalls.addLast(key);
        repeatedToolCalls.merge(key, 1, Integer::sum);
        int window = Math.max(1, repeatWindowSize);
        while (recentToolCalls.size() > window) {
            String removed = recentToolCalls.removeFirst();
            repeatedToolCalls.computeIfPresent(removed, (ignored, count) -> count <= 1 ? null : count - 1);
        }
    }

    private String callKey(String toolName, String arguments) {
        return (toolName == null ? "" : toolName.trim()) + "\n" + (arguments == null ? "" : arguments.trim());
    }

    private boolean intentAllows(String toolName) {
        String text = userMessage == null ? "" : userMessage.trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) return true;
        return switch (toolName.trim().toLowerCase(Locale.ROOT)) {
            case "run_bash", "write_file" -> containsAny(text, "排查", "代码", "项目", "命令", "bash", "执行", "日志");
            case "read_file" -> containsAny(text, "skill", "技能", "项目", "代码", "文件", "排查", "读取", "查看");
            case "call_mcp_tool", "query_feedback" -> containsAny(text, "查询", "反馈", "库存", "mcp", "巡检", "今日", "数据", "抓取", "获取", "搜索");
            case "query_cases" -> containsAny(text, "case", "案例", "历史", "查询");
            case "task", "dispatch_subagents" -> containsAny(text, "子agent", "subagent", "并行", "拆分", "复杂任务", "子任务");
            default -> true;
        };
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public record ToolCallDecision(boolean allowed, String code, String message) {
        public static ToolCallDecision allow(String toolName) {
            return new ToolCallDecision(true, "ALLOWED", "允许调用工具：" + toolName);
        }

        public static ToolCallDecision reject(String code, String message) {
            return new ToolCallDecision(false, code, message);
        }
    }
}
