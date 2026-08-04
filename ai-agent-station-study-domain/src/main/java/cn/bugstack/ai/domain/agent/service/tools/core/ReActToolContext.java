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

    @Builder.Default
    private int maxToolCalls = 10;

    @Builder.Default
    private int maxModelRounds = 8;

    @Builder.Default
    private AtomicInteger currentStep = new AtomicInteger(0);

    @Builder.Default
    private Deque<String> recentToolCalls = new ArrayDeque<>();

    @Builder.Default
    private Map<String, Integer> repeatedToolCalls = new HashMap<>();

    private BooleanSupplier cancellationCheck;

    /**
     * 为一次工具动作申请预算。返回负数表示已耗尽预算。
     * AtomicInteger 保证同一请求内并发工具回调不会重复使用步数。
     */
    public int consumeStep() {
        while (true) {
            int current = currentStep.get();
            if (current >= maxSteps || current >= maxToolCalls) return -1;
            if (currentStep.compareAndSet(current, current + 1)) return current + 1;
        }
    }

    public boolean isCancellationRequested() {
        return cancellationCheck != null && cancellationCheck.getAsBoolean();
    }

    /** 记录最近工具调用，达到 5 次相同调用时强制停止。 */
    public synchronized boolean repeatedCallExceeded(String toolName, String description) {
        String key = toolName + "\n" + (description == null ? "" : description);
        recentToolCalls.addLast(key);
        repeatedToolCalls.merge(key, 1, Integer::sum);
        while (recentToolCalls.size() > 20) {
            String removed = recentToolCalls.removeFirst();
            repeatedToolCalls.computeIfPresent(removed, (ignored, count) -> count <= 1 ? null : count - 1);
        }
        return repeatedToolCalls.getOrDefault(key, 0) >= 2;
    }
}
