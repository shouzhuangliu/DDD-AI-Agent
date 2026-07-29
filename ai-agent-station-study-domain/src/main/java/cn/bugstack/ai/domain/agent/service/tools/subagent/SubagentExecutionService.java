package cn.bugstack.ai.domain.agent.service.tools.subagent;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool.SubagentCancellationException;
import cn.bugstack.ai.domain.agent.service.tools.internal.BashTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileReadTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileWriteTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryCaseTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryFeedbackTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.RetrieveToolCallTool;
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpCallTool;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskState;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 双线程 Subagent 执行器。对齐 angx executor.py：调度线程登记/超时/发布，执行线程跑独立模型上下文。
 * <p>
 * <ul>
 *   <li>{@link #MAX_CONCURRENT} = 3：每个父执行同时最多 3 个并行子任务，超出在信号量上排队。</li>
 *   <li>子 Agent 继承父工具集（按 ctx.allowedTools 镜像），但剔除 task / dispatch_subagents，禁止递归。</li>
 *   <li>子 Agent 用独立消息上下文 + 独立 ReActToolContext（共享父 emitter 仅发 subagent_* 事件）。</li>
 *   <li>终态幂等写入；SSE 事件 subagent_started/completed/failed。</li>
 * </ul>
 */
@Slf4j
@Service
public class SubagentExecutionService {

    /** 单个父执行同时并行的子任务上限。对齐 angx MAX_CONCURRENT_SUBAGENTS=3。 */
    public static final int MAX_CONCURRENT = 3;
    private static final long TIMEOUT_SECONDS = 60;
    private static final String SUBAGENT_SYSTEM_PROMPT = "你是一个独立 Subagent。只完成分配给你的单一任务，必要时使用工具，最后用简洁中文返回结果。不要创建新的 Subagent。";

    private final ApplicationContext applicationContext;
    private final FileReadTool fileReadTool;
    private final FileWriteTool fileWriteTool;
    private final BashTool bashTool;
    private final McpCallTool mcpCallTool;
    private final RetrieveToolCallTool retrieveToolCallTool;
    private final QueryCaseTool queryCaseTool;
    private final QueryFeedbackTool queryFeedbackTool;
    private final ISubagentTaskRepository taskRepository;

    private ExecutorService scheduler;
    private ExecutorService executor;
    private final ConcurrentMap<String, SubagentTaskState> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<SubagentTaskState>> futures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<String>> childFutures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Semaphore> executionLimits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> executionTaskCounts = new ConcurrentHashMap<>();

    public SubagentExecutionService(ApplicationContext applicationContext,
                                    FileReadTool fileReadTool,
                                    FileWriteTool fileWriteTool,
                                    BashTool bashTool,
                                    McpCallTool mcpCallTool,
                                    RetrieveToolCallTool retrieveToolCallTool,
                                    QueryCaseTool queryCaseTool,
                                    QueryFeedbackTool queryFeedbackTool) {
        this(applicationContext, fileReadTool, fileWriteTool, bashTool, mcpCallTool,
                retrieveToolCallTool, queryCaseTool, queryFeedbackTool, null);
    }

    @Autowired
    public SubagentExecutionService(ApplicationContext applicationContext,
                                    FileReadTool fileReadTool,
                                    FileWriteTool fileWriteTool,
                                    BashTool bashTool,
                                    McpCallTool mcpCallTool,
                                    RetrieveToolCallTool retrieveToolCallTool,
                                    QueryCaseTool queryCaseTool,
                                    QueryFeedbackTool queryFeedbackTool,
                                    ISubagentTaskRepository taskRepository) {
        this.applicationContext = applicationContext;
        this.fileReadTool = fileReadTool;
        this.fileWriteTool = fileWriteTool;
        this.bashTool = bashTool;
        this.mcpCallTool = mcpCallTool;
        this.retrieveToolCallTool = retrieveToolCallTool;
        this.queryCaseTool = queryCaseTool;
        this.queryFeedbackTool = queryFeedbackTool;
        this.taskRepository = taskRepository;
    }

    @PostConstruct
    public void init() {
        persist(() -> taskRepository.markInterruptedTasks());
        // scheduler 必须多线程：每个 runTask 在 scheduler 线程里阻塞 future.get 等 executor，
        // 单线程会让多个任务串行排队（原 bug1）。线程数 ≥ MAX_CONCURRENT 才能保证并行提交。
        scheduler = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> { Thread t = new Thread(r, "subagent-scheduler"); t.setDaemon(true); return t; });
        executor = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> { Thread t = new Thread(r, "subagent-executor"); t.setDaemon(true); return t; });
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        if (executor != null) executor.shutdownNow();
    }

    /**
     * 提交一个子任务，立即返回 taskId（非阻塞）。{@link #await} 阻塞等终态。
     */
    public String submit(ReActToolContext parent, String description, String prompt) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SubagentTaskState state = SubagentTaskState.builder()
                .taskId(taskId).executionId(parent.getExecutionId()).agentId(parent.getAgentId())
                .description(description).status("PENDING").build();
        tasks.put(taskId, state);
        futures.put(taskId, new CompletableFuture<>());
        persist(() -> taskRepository.create(state));
        String executionKey = executionKey(parent);
        Semaphore sem = executionLimits.computeIfAbsent(executionKey, k -> new Semaphore(MAX_CONCURRENT));
        executionTaskCounts.computeIfAbsent(executionKey, k -> new AtomicInteger()).incrementAndGet();
        try {
            scheduler.submit(() -> runTask(parent, state, sem, prompt, executionKey));
        } catch (RuntimeException exception) {
            decrementExecutionTaskCount(executionKey, sem);
            markTerminal(state, "FAILED", safeMessage(exception));
            throw exception;
        }
        return taskId;
    }

    /** 阻塞等待任务终态。超时返回当时状态（可能仍在运行）。 */
    public SubagentTaskState await(String taskId, long timeoutMs) {
        CompletableFuture<SubagentTaskState> f = futures.get(taskId);
        if (f == null) return tasks.get(taskId);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return tasks.get(taskId);
        }
    }

    /**
     * 并行提交多个子任务（截断到 {@link #MAX_CONCURRENT}），等全部终态，聚合结果。
     * 并行不依赖 Spring AI 同批 tool_calls 并发，由本服务线程池保证。
     */
    public String dispatchAndWait(ReActToolContext parent, List<TaskInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return "未解析到任何子任务";
        if (inputs.size() > MAX_CONCURRENT) {
            log.warn("dispatch 截断了 {} 个超出并行的子任务（上限 {}）", inputs.size() - MAX_CONCURRENT, MAX_CONCURRENT);
            inputs = new ArrayList<>(inputs.subList(0, MAX_CONCURRENT));
        }
        // 全部并行提交（submit 非阻塞，立即返回；runTask 在 scheduler 多线程里各自等 executor）
        List<String> taskIds = new ArrayList<>();
        List<CompletableFuture<SubagentTaskState>> waiters = new ArrayList<>();
        for (TaskInput in : inputs) {
            String id = submit(parent, in.description(), in.prompt());
            taskIds.add(id);
            emitEvent(parent, "subagent_started", id, Map.of("description", in.description()));
            waiters.add(futures.get(id));
        }
        // 并行等全部：allOf 一个总超时，而非逐个 await（原 bug3：逐个 await 会让总等待=N×单个超时）。
        // 任务本就并行跑在 executor 上，总耗时≈最慢一个。
        long totalTimeoutMs = (TIMEOUT_SECONDS + 5) * 1000L;
        CompletableFuture<Void> all = CompletableFuture.allOf(waiters.toArray(new CompletableFuture[0]));
        try {
            all.get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 总超时后必须结束未完成任务，避免后台任务和内存状态无限残留。
            for (String taskId : taskIds) timeoutIfActive(taskId);
        }
        // 汇总每个任务终态
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < taskIds.size(); i++) {
            String id = taskIds.get(i);
            SubagentTaskState st = tasks.get(id);
            if (st == null) { sb.append("- [").append(inputs.get(i).description()).append("] 任务丢失\n"); continue; }
            if ("COMPLETED".equals(st.getStatus())) {
                emitEvent(parent, "subagent_completed", id, Map.of("status", st.getStatus(), "result", st.getResult()));
                sb.append("- [").append(inputs.get(i).description()).append("] 完成: ").append(st.getResult()).append("\n");
            } else {
                String err = st.getErrorMessage() == null ? st.getStatus() : st.getErrorMessage();
                emitEvent(parent, terminalEventType(st.getStatus()), id, Map.of("status", st.getStatus(), "error", err));
                sb.append("- [").append(inputs.get(i).description()).append("] ").append(st.getStatus()).append(": ").append(err).append("\n");
            }
            cleanup(id);
        }
        return sb.toString().trim();
    }

    /** 兼容旧入口：提交并阻塞等结果字符串。 */
    public String submitAndWait(ReActToolContext parent, String description, String prompt) {
        String taskId = submit(parent, description, prompt);
        emitEvent(parent, "subagent_started", taskId, Map.of("description", description));
        SubagentTaskState st = await(taskId, (TIMEOUT_SECONDS + 5) * 1000L);
        if (st == null) return "Subagent 任务丢失: " + description;
        String result = "COMPLETED".equals(st.getStatus()) ? st.getResult()
                : ("Subagent " + st.getStatus() + ": " + (st.getErrorMessage() == null ? description : st.getErrorMessage()));
        if ("COMPLETED".equals(st.getStatus())) {
            emitEvent(parent, "subagent_completed", taskId, Map.of("status", st.getStatus(), "result", st.getResult()));
        } else {
            emitEvent(parent, terminalEventType(st.getStatus()), taskId,
                    Map.of("status", st.getStatus(), "error", st.getErrorMessage() == null ? st.getStatus() : st.getErrorMessage()));
        }
        cleanup(taskId);
        return result;
    }

    public SubagentTaskState find(String taskId) { return tasks.get(taskId); }

    /**
     * 请求取消一个子任务。设置 cancelRequested 标志：
     * <ul>
     *   <li>未启动：runTask 启动时检测并直接置 CANCELLED。</li>
     *   <li>运行中：执行体在工具调用间隙（{@link #runInChildContext} 抛 CancellationException）
     *       检测并置 CANCELLED。真正中断 Spring AI 内部工具循环需 Phase C 拆循环后才能立即生效，
     *       本轮仅在子 Agent 工具回调边界做检查点（子 Agent 通常有多轮工具调用，可在下一轮退出）。</li>
     *   <li>已终态：幂等忽略。</li>
     * </ul>
     * 返回 false 表示任务不存在或已终态无法取消。
     */
    public boolean cancel(String taskId) {
        SubagentTaskState state = tasks.get(taskId);
        if (state == null) return false;
        if (isTerminal(state.getStatus())) return false;
        state.setCancelRequested(true);
        Future<String> child = childFutures.get(taskId);
        if (child != null) child.cancel(true);
        markTerminal(state, "CANCELLED", "Subagent cancellation requested");
        persist(() -> taskRepository.markCancelRequested(taskId));
        return true;
    }

    private void timeoutIfActive(String taskId) {
        SubagentTaskState state = tasks.get(taskId);
        if (state == null || isTerminal(state.getStatus())) return;
        state.setCancelRequested(true);
        Future<String> child = childFutures.get(taskId);
        if (child != null) child.cancel(true);
        markTerminal(state, "TIMED_OUT", "Subagent batch timeout");
    }

    public void cleanup(String taskId) {
        SubagentTaskState st = tasks.get(taskId);
        if (st != null && isTerminal(st.getStatus())) {
            tasks.remove(taskId);
            futures.remove(taskId);
        }
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status) || "CANCELLED".equals(status);
    }

    private static String terminalEventType(String status) {
        if ("CANCELLED".equals(status)) return "subagent_cancelled";
        if ("TIMED_OUT".equals(status)) return "subagent_timed_out";
        return "subagent_failed";
    }

    private void runTask(ReActToolContext parent, SubagentTaskState state, Semaphore sem,
                         String prompt, String executionKey) {
        boolean acquired = false;
        try {
            if (!sem.tryAcquire(5, TimeUnit.SECONDS)) {
                markTerminal(state, "FAILED", "超过单次执行的 Subagent 并发上限");
                return;
            }
            acquired = true;
            // 启动前已被取消，直接置 CANCELLED
            if (state.isCancelRequested()) {
                markTerminal(state, "CANCELLED", "用户取消");
                return;
            }
            state.setStatus("RUNNING");
            state.setStartedAt(LocalDateTime.now());
            persist(() -> taskRepository.markRunning(state.getTaskId()));
            Future<String> f = executor.submit(() -> runInChildContext(parent, state, prompt));
            childFutures.put(state.getTaskId(), f);
            try {
                String result = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (state.isCancelRequested()) {
                    markTerminal(state, "CANCELLED", "用户取消");
                } else {
                    markTerminal(state, "COMPLETED", result);
                }
            } catch (TimeoutException e) {
                f.cancel(true);
                markTerminal(state, "TIMED_OUT", "Subagent 执行超时");
            } catch (SubagentCancellationException e) {
                markTerminal(state, "CANCELLED", e.getMessage());
            } catch (Exception e) {
                if (state.isCancelRequested()) {
                    markTerminal(state, "CANCELLED", "用户取消");
                } else {
                    markTerminal(state, "FAILED", safeMessage(e));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markTerminal(state, state.isCancelRequested() ? "CANCELLED" : "FAILED", "调度被中断");
        } finally {
            childFutures.remove(state.getTaskId());
            // 只有成功 acquire 才 release，避免许可证泄漏突破并发上限（原 bug2）
            if (acquired) sem.release();
            decrementExecutionTaskCount(executionKey, sem);
        }
    }

    /** 在执行线程内设置子上下文并跑子 Agent。子上下文镜像父 allowedTools，剔除 task/dispatch_subagents。 */
    protected String runInChildContext(ReActToolContext parent, SubagentTaskState state, String prompt) {
        ReActToolContext child = ReActToolContext.builder()
                .sessionId(parent.getSessionId() + "#" + state.getTaskId())
                .agentId(parent.getAgentId())
                .emitter(parent.getEmitter())
                .workDir(parent.getWorkDir())
                .boundSkillIds(parent.getBoundSkillIds())
                .boundMcpIds(parent.getBoundMcpIds())
                .allowedTools(parent.getAllowedTools())
                .executionId(parent.getExecutionId())
                .modelId(parent.getModelId())
                .maxSteps(parent.getMaxSteps())
                .cancellationCheck(() -> state.isCancelRequested() || parent.isCancellationRequested())
                .build();
        ReActToolContextHolder.set(child);
        try {
            fileReadTool.resetStep();
            fileWriteTool.resetStep();
            bashTool.resetStep();
            mcpCallTool.resetStep();

            OpenAiChatModel model = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(parent.getModelId()), OpenAiChatModel.class);
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(selectChildTools(parent.getAllowedTools()))
                    .build().getToolCallbacks();
            String result = runChildReActLoop(model, prompt, callbacks, state, parent, parent.getMaxSteps());
            return result == null || result.isBlank() ? "（子 Agent 未返回内容）" : result;
        } finally {
            ReActToolContextHolder.clear();
        }
    }

    /** 子 Agent 工具集：镜像父 allowedTools，但剔除 task/dispatch_subagents 防递归。 */
    private String runChildReActLoop(OpenAiChatModel model, String prompt,
                                     ToolCallback[] callbacks, SubagentTaskState state,
                                     ReActToolContext parent, int configuredMaxSteps) {
        Map<String, ToolCallback> callbackByName = new HashMap<>();
        for (ToolCallback callback : callbacks) {
            callbackByName.put(callback.getToolDefinition().name(), callback);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SUBAGENT_SYSTEM_PROMPT));
        messages.add(new UserMessage(prompt));
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(java.util.Arrays.asList(callbacks)).build();

        int maxSteps = Math.max(1, configuredMaxSteps);
        for (int round = 0; round < maxSteps; round++) {
            checkCancellation(state, parent);
            ChatResponse response = model.call(new Prompt(messages, options));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return "子 Agent 未返回有效响应";
            }
            AssistantMessage assistant = response.getResult().getOutput();
            messages.add(assistant);
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                return assistant.getText() == null ? "子 Agent 未返回内容" : assistant.getText();
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                checkCancellation(state, parent);
                ToolCallback callback = callbackByName.get(call.name());
                String result = callback == null ? "未授权的工具: " + call.name() : callback.call(call.arguments());
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result == null ? "" : result));
            }
            messages.add(new ToolResponseMessage(responses));
        }
        return "子 Agent 已达到 " + maxSteps + " 步上限";
    }

    private void checkCancellation(SubagentTaskState state, ReActToolContext parent) {
        if (state.isCancelRequested() || parent.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new SubagentCancellationException();
        }
    }

    private Object[] selectChildTools(List<String> allowedTools) {
        List<Object> tools = new ArrayList<>();
        if (allowedTools == null) return tools.toArray();
        if (allowedTools.contains(ReActToolAllowlistPolicy.READ_FILE)) tools.add(fileReadTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.WRITE_FILE)) tools.add(fileWriteTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.RUN_BASH)) tools.add(bashTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) tools.add(mcpCallTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL)) tools.add(retrieveToolCallTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_CASES)) tools.add(queryCaseTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_FEEDBACK)) tools.add(queryFeedbackTool);
        // task / dispatch_subagents 不加入，禁止子 Agent 再委派
        return tools.toArray();
    }

    private synchronized void markTerminal(SubagentTaskState state, String status, String payload) {
        if (isTerminal(state.getStatus())) return; // 幂等：终态只写一次
        state.setStatus(status);
        state.setCompletedAt(LocalDateTime.now());
        if ("COMPLETED".equals(status)) state.setResult(payload);
        else state.setErrorMessage(payload);
        persist(() -> taskRepository.finish(state));
        CompletableFuture<SubagentTaskState> f = futures.get(state.getTaskId());
        if (f != null) f.complete(state);
    }

    private void emitEvent(ReActToolContext ctx, String type, String taskId, Map<String, Object> extra) {
        if (ctx == null || ctx.getEmitter() == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("taskId", taskId);
        event.put("sessionId", ctx.getSessionId());
        if (extra != null) event.putAll(extra);
        synchronized (ctx.getEmitter()) {
            try {
                ctx.getEmitter().send("data: " + JSON.toJSONString(event) + "\n\n");
            } catch (IOException e) {
                log.warn("推送 subagent SSE 失败 taskId={} reason={}", taskId, e.getMessage());
            }
        }
    }

    private static String executionKey(ReActToolContext ctx) {
        String k = ctx.getExecutionId();
        if (k == null || k.isBlank()) k = ctx.getAgentId();
        if (k == null || k.isBlank()) k = "default";
        return k;
    }

    private void decrementExecutionTaskCount(String executionKey, Semaphore semaphore) {
        AtomicInteger count = executionTaskCounts.get(executionKey);
        if (count == null || count.decrementAndGet() > 0) return;
        executionTaskCounts.remove(executionKey, count);
        executionLimits.remove(executionKey, semaphore);
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void persist(Runnable action) {
        if (taskRepository == null) return;
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("Subagent task persistence failed: {}", exception.getMessage());
        }
    }

    /** 批量任务输入。 */
    public record TaskInput(String description, String prompt) {
    }
}
