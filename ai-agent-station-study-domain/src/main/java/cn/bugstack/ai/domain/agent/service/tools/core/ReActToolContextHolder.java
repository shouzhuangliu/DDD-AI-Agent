package cn.bugstack.ai.domain.agent.service.tools.core;

/**
 * ReAct 工具上下文 ThreadLocal 持有者。
 * <p>
 * Spring AI 在 {@code .call()} 内部跑工具循环时，会切线程/在同线程回调 @Tool 方法。
 * 这里用 ThreadLocal 让工具方法能取到当前请求的 emitter 与沙箱目录。
 * 由 {@code ReActExecuteStrategy} 在调用前 set，finally 中 clear。
 *
 * @author ai-agent-station-study
 */
public final class ReActToolContextHolder {

    private static final ThreadLocal<ReActToolContext> HOLDER = new ThreadLocal<>();

    private ReActToolContextHolder() {
    }

    public static void set(ReActToolContext context) {
        HOLDER.set(context);
    }

    public static ReActToolContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
