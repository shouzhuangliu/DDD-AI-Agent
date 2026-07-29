package cn.bugstack.ai.domain.agent.service.tools.core;

import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteResultEntity;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 内部工具基类：封装 SSE 推送与沙箱路径校验。
 * <p>
 * 工具方法被 Spring AI 在工具循环中回调时，通过 {@link ReActToolContextHolder} 拿到当前请求上下文，
 * 在执行工具前后推送 action / observation，让 ReAct 过程在界面上可见。
 *
 * @author ai-agent-station-study
 */
public abstract class AbstractReActTool {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 工具调用步数计数器（每次调用递增，仅用于 SSE 展示） */
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    /** 推送 action：模型决定调用本工具 */
    protected void emitAction(String toolName, String description) {
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx == null) {
            return;
        }
        if (ctx.isCancellationRequested()) {
            throw new SubagentCancellationException();
        }
        int step = ctx.consumeStep();
        if (step < 0) {
            String message = "ReAct 工具步数已达到上限（" + ctx.getMaxSteps() + "），本次工具调用已停止。";
            send(ctx, ReActExecuteResultEntity.createError(message, ctx.getSessionId()));
            throw new IllegalStateException(message);
        }
        if (ctx.repeatedCallExceeded(toolName, description)) {
            String message = "检测到相同工具调用重复执行，已强制停止以避免循环。";
            send(ctx, ReActExecuteResultEntity.createError(message, ctx.getSessionId()));
            throw new IllegalStateException(message);
        }
        send(ctx, ReActExecuteResultEntity.createAction(step, toolName, description, ctx.getSessionId()));
    }

    public static class SubagentCancellationException extends RuntimeException {
        public SubagentCancellationException() {
            super("Subagent cancellation requested");
        }
    }

    /** 推送 observation：工具执行结果 */
    protected void emitObservation(String toolName, String observation) {
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx == null) {
            return;
        }
        int step = ctx.getCurrentStep().get();
        send(ctx, ReActExecuteResultEntity.createObservation(step, toolName, observation, ctx.getSessionId()));
    }

    /** 推送错误 */
    protected void emitError(String content) {
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx == null) {
            return;
        }
        send(ctx, ReActExecuteResultEntity.createError(content, ctx.getSessionId()));
    }

    private void send(ReActToolContext ctx, ReActExecuteResultEntity result) {
        ResponseBodyEmitter emitter = ctx.getEmitter();
        if (emitter == null) {
            return;
        }
        try {
            String payload = JSON.toJSONString(result);
            String sessionId = ctx.getSessionId();
            if (sessionId != null && sessionId.contains("#")) {
                Map<String, Object> childEvent = new LinkedHashMap<>(JSON.parseObject(payload, Map.class));
                childEvent.put("type", "subagent_trace");
                childEvent.put("taskId", sessionId.substring(sessionId.lastIndexOf('#') + 1));
                emitter.send("data: " + JSON.toJSONString(childEvent) + "\n\n");
            } else {
                emitter.send("data: " + payload + "\n\n");
            }
        } catch (IOException e) {
            log.error("推送 ReAct SSE 失败: {}", e.getMessage());
        }
    }

    /**
     * 沙箱路径校验：将相对路径解析到 workDir 下，并确保最终路径不逃逸出 workDir。
     *
     * @return 解析后的绝对路径；若越界或非法返回 null
     */
    protected Path resolveInWorkDir(String relativePath) {
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx == null || ctx.getWorkDir() == null) {
            return null;
        }
        Path workDir = ctx.getWorkDir().toAbsolutePath().normalize();
        // 禁止绝对路径与盘符，强制在沙箱内
        String p = relativePath == null ? "" : relativePath.trim();
        if (p.isEmpty() || p.contains(":") || p.startsWith("/")) {
            return null;
        }
        Path resolved = workDir.resolve(p).normalize();
        if (!resolved.startsWith(workDir)) {
            return null; // 越界
        }
        return resolved;
    }

    protected String workDirString() {
        ReActToolContext ctx = ReActToolContextHolder.get();
        return ctx != null && ctx.getWorkDir() != null ? ctx.getWorkDir().toAbsolutePath().toString() : ".";
    }

    /** 重置步数计数（每次新请求由 strategy 调用） */
    public void resetStep() {
        stepCounter.set(0);
    }
}
