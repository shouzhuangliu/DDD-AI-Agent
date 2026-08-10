package cn.bugstack.ai.domain.agent.service.execute.react;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentExecutionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentExecutionState;
import cn.bugstack.ai.domain.agent.model.entity.AgentTodoItem;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentModeEnum;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessage;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessageMapper;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import cn.bugstack.ai.domain.agent.service.memory.ContextBudgetPolicy;
import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool.SubagentCancellationException;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileReadTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileWriteTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.BashTool;
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpToolDiscoveryTool;
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpToolHandleCallTool;
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpToolSchemaTool;
import cn.bugstack.ai.domain.agent.service.tools.subagent.SubagentTaskTool;
import cn.bugstack.ai.domain.agent.service.tools.subagent.DispatchSubagentsTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.RetrieveToolCallTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryCaseTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryFeedbackTool;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;

/**
 * ReAct 执行策略：模型自主推理 + 工具调用循环。
 * <p>
 * MCP 工具采用 Progressive Disclosure（渐进式披露）：
 * 系统提示词只列出 MCP 工具名和描述，不挂全量 schema（防上下文膨胀）；
 * LLM 决定使用哪个 MCP 工具后，通过 call_mcp_tool 内部工具动态调用。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Service
public class ReActExecuteStrategy implements IExecuteStrategy {

    public static String normalizeFinalContent(String content, String modelId) {
        if (content != null && !content.isBlank()) return content;
        return "模型 " + (modelId == null || modelId.isBlank() ? "未知" : modelId)
                + " 返回空内容，未生成可显示回复。请检查模型响应或稍后重试。";
    }

    public static boolean isRateLimitError(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("429")
                || normalized.contains("rpm exhausted")
                || normalized.contains("quota_exceeded")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests");
    }

    /**
     * Provider unavailable/overloaded errors are not recoverable by repeatedly sending the same request.
     * Detect them explicitly so the agent can stop quickly and give the user an actionable model-switch hint.
     */
    public static boolean isServiceUnavailableError(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("503")
                || normalized.contains("service unavailable")
                || normalized.contains("service_unavailable")
                || normalized.contains("service too busy")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("overloaded");
    }

    public static boolean isLowValueRequest(String message) {
        if (message == null) return true;
        String normalized = message.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return true;
        return Set.of("1", "ok", "okay", "yes", "y", "no", "n", "hi", "hello", "继续", "好的", "测试")
                .contains(normalized);
    }

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    @Resource
    private IAgentExecutionRepository executionRepository;

    @Resource
    private ReActToolProperties properties;

    @Resource
    private FileReadTool fileReadTool;

    @Resource
    private FileWriteTool fileWriteTool;

    @Resource
    private BashTool bashTool;

    @Resource
    private McpToolDiscoveryTool mcpToolDiscoveryTool;

    @Resource
    private McpToolHandleCallTool mcpToolHandleCallTool;

    @Resource
    private McpToolSchemaTool mcpToolSchemaTool;

    @Resource
    private SubagentTaskTool subagentTaskTool;

    @Resource
    private DispatchSubagentsTool dispatchSubagentsTool;

    @Resource
    private RetrieveToolCallTool retrieveToolCallTool;

    @Resource
    private QueryCaseTool queryCaseTool;

    @Resource
    private QueryFeedbackTool queryFeedbackTool;

    @Resource
    private ReActToolAllowlistPolicy toolAllowlistPolicy;

    @Resource
    private SkillScannerService skillScannerService;

    @Resource
    private AgentRuntimeBindingService agentRuntimeBindingService;

    @Resource
    private ChatMessageRecorder messageRecorder;

    @Resource
    private ContextBudgetPolicy contextBudgetPolicy;

    @Resource
    private AgentWorkspaceService agentWorkspaceService;

    @Resource
    private AgentExecutionCancellationRegistry cancellationRegistry;

    @Override

    public String getType() {
        return AiAgentModeEnum.REACT.getCode();
    }

    @Override
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        String sessionId = requestParameter.getSessionId();
        String agentId = requestParameter.getAiAgentId();
        log.info("🧠 ReAct 执行开始, agentId={}, sessionId={}, message={}", agentId, sessionId, requestParameter.getMessage());

        AiAgentVO agent = repository.queryAgentById(agentId);
        if (agent == null) {
            emitter.send("data: " + JSON.toJSONString(
                    ReActExecuteResultEntity.createError("Agent 不存在: " + agentId, sessionId)) + "\n\n");
            return;
        }

        AgentRuntimeBindingService.AgentRuntimeBindings bindings =
                agentRuntimeBindingService.assemble(agentId, properties.getWorkDir(), true);
        List<String> skillIds = bindings.getSkillIds();
        List<String> mcpIds = bindings.getMcpIds();
        Path workDir = bindings.getWorkspace();

        ReActToolContextHolder.set(ReActToolContext.builder()
                .sessionId(sessionId)
                .userMessage(requestParameter.getMessage())
                .agentId(agentId)
                .emitter(emitter)
                .workDir(workDir)
                .boundSkillIds(skillIds)
                .boundMcpIds(mcpIds)
                .build());

        fileReadTool.resetStep();
        fileWriteTool.resetStep();
        bashTool.resetStep();
        mcpToolHandleCallTool.resetStep();

        String executionId = null;
        try {
            String selectedModelId = ModelSelectionService.select(requestParameter.getModelId(), agent.getModelId());
            executionId = UUID.randomUUID().toString();
            cancellationRegistry.register(executionId);
            int maxSteps = requestParameter.getMaxStep() == null || requestParameter.getMaxStep() <= 0
                    ? 30 : requestParameter.getMaxStep();
            executionRepository.create(AgentExecutionState.builder()
                    .executionId(executionId).sessionId(sessionId).agentId(agentId).modelId(selectedModelId)
                    .routeType(requestParameter.getRouteType() == null ? getType() : requestParameter.getRouteType())
                    .status("RUNNING").maxCycles(5).maxSteps(maxSteps).stateJson("{}")
                    .startedAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
            List<AgentTodoItem> todos = new ArrayList<>();
            todos.add(AgentTodoItem.builder().todoId(UUID.randomUUID().toString())
                    .content(requestParameter.getMessage()).status("IN_PROGRESS").owner("REACT")
                    .position(0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
            sendTodoEvent(emitter, executionId, sessionId, todos);
            List<String> explicitToolIds = bindings.getExplicitToolIds();
            List<String> allowedTools = new ArrayList<>(bindings.getEffectiveToolIds());
            if (!allowedTools.contains(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL)) {
                allowedTools.add(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL);
            }
            String currentExecutionId = executionId;
            ReActToolContextHolder.set(ReActToolContext.builder()
                    .sessionId(sessionId).userMessage(requestParameter.getMessage()).agentId(agentId).emitter(emitter).workDir(workDir)
                    .boundSkillIds(skillIds).boundMcpIds(mcpIds)
                    .allowedTools(allowedTools).explicitToolIds(explicitToolIds)
                    .executionId(executionId).modelId(selectedModelId).maxSteps(maxSteps)
                    .cancellationCheck(() -> cancellationRegistry.isCancelled(currentExecutionId)).build());
            sendStateEvent(emitter, executionId, sessionId, 0, 0, "RUNNING");
            String modelBeanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(selectedModelId);
            OpenAiChatModel chatModel = applicationContext.getBean(modelBeanName, OpenAiChatModel.class);
            log.info("ReAct 使用模型，agentId={}，sessionId={}，modelId={}", agentId, sessionId, selectedModelId);

            String systemPrompt = buildSystemPrompt(bindings, allowedTools, explicitToolIds);

            // 内置工具按 Agent 白名单动态暴露，避免普通业务反馈触发 Bash/读项目等高风险动作。
            MethodToolCallbackProvider internalTools = MethodToolCallbackProvider.builder()
                    .toolObjects(selectToolObjects(allowedTools))
                    .build();

            // 记录用户消息
            List<HistoryMessage> historyBeforeUser = messageRecorder.getHistory(sessionId);
            messageRecorder.recordUser(sessionId, agentId, 0, requestParameter.getMessage());

            // 从 DB 加载历史（仅 user/assistant 纯文本，无 tool 中间态）
            List<HistoryMessage> history = historyBeforeUser;

            // 消息 Map 列表 → fold 管线
            java.util.List<java.util.Map<String, Object>> msgMaps = HistoryMessageMapper.toMaps(history);
            java.util.Map<String, Object> currentMessage = new java.util.LinkedHashMap<>();
            currentMessage.put("role", "user");
            currentMessage.put("content", requestParameter.getMessage());
            msgMaps.add(currentMessage);
            ContextBudgetPolicy.BudgetDecision budget = contextBudgetPolicy.decide(
                    selectedModelId, systemPrompt, toolDescription(internalTools.getToolCallbacks()), msgMaps);
            msgMaps = MemoryFoldingPipeline.fold(msgMaps, budget);

            // 转 Spring AI Message
            String finalContent = isLowValueRequest(requestParameter.getMessage())
                    ? "请补充具体问题或业务对象，我再为你查询。"
                    : callReActLoop(chatModel, systemPrompt, msgMaps,
                    internalTools.getToolCallbacks(), selectedModelId, maxSteps);
            ReActToolContext executionContext = ReActToolContextHolder.get();
            int usedSteps = executionContext == null ? 0 : executionContext.getCurrentStep().get();
            executionRepository.updateProgress(executionId, 0, usedSteps,
                    JSON.toJSONString(Map.of("toolSteps", usedSteps)));
            sendStateEvent(emitter, executionId, sessionId, 0, usedSteps, "RUNNING");

            boolean emptyResponse = finalContent == null || finalContent.isBlank();
            finalContent = normalizeFinalContent(finalContent, selectedModelId);

            // 记录 LLM 调用日志
            try {
                ChatMessageRecorder.LlmLogEntry logEntry = ChatMessageRecorder.LlmLogEntry.builder()
                        .sessionId(sessionId).agentId(agentId)
                        .modelName(selectedModelId)
                        .mode(AiAgentModeEnum.REACT.getCode())
                        .durationMs(0).status(emptyResponse ? "failed" : "success")
                        .errorMessage(emptyResponse ? finalContent : null)
                        .historyMsgCount(history.size())
                        .foldedMsgCount(msgMaps.size())
                        .systemPromptLen(systemPrompt.length())
                        .userMessageLen(requestParameter.getMessage().length())
                        .assistantResponseLen(finalContent != null ? finalContent.length() : 0)
                        .build();
                messageRecorder.recordLlmLog(logEntry);
            } catch (Exception ignored) {}

            log.info("🧠 ReAct 执行完成: {}", finalContent);

            // 记录 assistant 消息
            messageRecorder.recordAssistant(sessionId, agentId, 0, 0, finalContent, null);
            todos.get(0).setStatus("COMPLETED");
            todos.get(0).setUpdatedAt(LocalDateTime.now());
            sendTodoEvent(emitter, executionId, sessionId, todos);
            executionRepository.finish(executionId, "COMPLETED", finalContent, null);

            emitter.send("data: " + JSON.toJSONString(
                    ReActExecuteResultEntity.createFinal(finalContent, sessionId)) + "\n\n");
            emitter.send("data: " + JSON.toJSONString(
                    ReActExecuteResultEntity.createComplete(sessionId)) + "\n\n");

        } catch (Exception e) {
            if (e instanceof java.util.concurrent.CancellationException) {
                ReActToolContext cancelledContext = ReActToolContextHolder.get();
                String cancelledExecutionId = cancelledContext == null ? executionId : cancelledContext.getExecutionId();
                if (cancelledExecutionId != null) {
                    executionRepository.finish(cancelledExecutionId, "CANCELLED", null, "User cancelled execution");
                    sendStateEvent(emitter, cancelledExecutionId, sessionId, 0, 0, "CANCELLED");
                }
                try {
                    emitter.send("data: " + JSON.toJSONString(
                            ReActExecuteResultEntity.createComplete(sessionId)) + "\n\n");
                } catch (Exception ignored) {
                }
                return;
            }
            log.error("ReAct 执行异常: {}", e.getMessage(), e);
            ReActToolContext context = ReActToolContextHolder.get();
            if (context != null && context.getExecutionId() != null) {
                String status = cancellationRegistry.isCancelled(context.getExecutionId()) ? "CANCELLED" : "FAILED";
                executionRepository.finish(context.getExecutionId(), status, null, e.getMessage());
            }
            try {
                emitter.send("data: " + JSON.toJSONString(
                        ReActExecuteResultEntity.createError(e.getMessage(), sessionId)) + "\n\n");
            } catch (Exception ignored) {
            }
        } finally {
            cancellationRegistry.remove(executionId);
            ReActToolContextHolder.clear();
        }
    }

    private void sendStateEvent(ResponseBodyEmitter emitter, String executionId, String sessionId,
                                int cycle, int step, String status) {
        try {
            emitter.send("data: " + JSON.toJSONString(Map.of(
                    "type", "state_updated", "executionId", executionId, "sessionId", sessionId,
                    "cycle", cycle, "step", step, "status", status)) + "\n\n");
        } catch (Exception e) {
            log.debug("发送执行状态事件失败: {}", e.getMessage());
        }
    }

    private void sendTodoEvent(ResponseBodyEmitter emitter, String executionId, String sessionId,
                               List<AgentTodoItem> todos) {
        try {
            emitter.send("data: " + JSON.toJSONString(Map.of(
                    "type", "todo_updated", "executionId", executionId,
                    "sessionId", sessionId, "todos", todos)) + "\n\n");
        } catch (java.util.concurrent.CancellationException e) {
            ReActToolContext context = ReActToolContextHolder.get();
            String cancelledExecutionId = context == null ? executionId : context.getExecutionId();
            if (cancelledExecutionId != null) {
                executionRepository.finish(cancelledExecutionId, "CANCELLED", null, "用户取消执行");
                sendStateEvent(emitter, cancelledExecutionId, sessionId, 0, 0, "CANCELLED");
            }
            try {
                emitter.send("data: " + JSON.toJSONString(
                        ReActExecuteResultEntity.createComplete(sessionId)) + "\n\n");
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
            log.debug("Todo event send failed: {}", ignored.getMessage());
        }
    }

    /**
     * 带重试的模型调用。失败后等 1s 重试,最多 2 次,仍失败则返回 fallback。
     */
    private String callReActLoop(ChatModel chatModel, String systemPrompt,
                                 List<Map<String, Object>> initialMessages, ToolCallback[] callbacks,
                                 String modelId, int maxSteps) {
        Map<String, ToolCallback> callbackByName = new java.util.HashMap<>();
        for (ToolCallback callback : callbacks) {
            callbackByName.put(callback.getToolDefinition().name(), callback);
        }
        List<Map<String, Object>> conversationMaps = new ArrayList<>(initialMessages);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(java.util.Arrays.asList(callbacks))
                .build();

        ReActToolContext loopContext = ReActToolContextHolder.get();
        int configuredRounds = loopContext == null ? 8 : loopContext.getMaxModelRounds();
        int boundedMaxSteps = Math.max(1, Math.min(maxSteps, configuredRounds));
        int emptyResponseRetries = 0;
        int consecutiveToolFailures = 0;
        for (int round = 0; round < boundedMaxSteps; round++) {
            ReActToolContext context = ReActToolContextHolder.get();
            if (context != null && context.isCancellationRequested()) {
                throw new java.util.concurrent.CancellationException("ReAct cancellation requested");
            }
            ContextBudgetPolicy.BudgetDecision budget = contextBudgetPolicy.decide(
                    modelId, systemPrompt, toolDescription(callbacks), conversationMaps);
            List<Message> conversation = new ArrayList<>();
            conversation.add(new SystemMessage(systemPrompt));
            conversation.addAll(HistoryMessageMapper.toSpringMessages(
                    MemoryFoldingPipeline.fold(conversationMaps, budget)));
            Object modelResult = callModelWithRetry(chatModel, conversation, options, modelId);
            if (modelResult instanceof String text) return text;
            ChatResponse response = (ChatResponse) modelResult;
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return "模型未返回有效响应";
            }
            AssistantMessage assistant = response.getResult().getOutput();
            conversationMaps.add(HistoryMessageMapper.toMap(assistant));
            if (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                try {
                    messageRecorder.recordAssistantToolCalls(
                            loopContext == null ? null : loopContext.getSessionId(),
                            loopContext == null ? null : loopContext.getAgentId(),
                            0, round + 1, assistant.getText(),
                            JSON.toJSONString(HistoryMessageMapper.toMap(assistant).get("tool_calls")));
                } catch (Exception exception) {
                    log.warn("assistant tool_calls 持久化失败: {}", exception.getMessage());
                }
            }
            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) return text;
                log.warn("模型返回空文本: modelId={}, round={}, response={}",
                        modelId, round + 1, JSON.toJSONString(response));
                if (emptyResponseRetries++ < 1) continue;
                return "";
            }

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            boolean cancellationRequested = false;
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                context = ReActToolContextHolder.get();
                if (context != null && context.isCancellationRequested()) {
                    cancellationRequested = true;
                }
                String toolName = toolCall.name();
                String toolArguments = toolCall.arguments();
                ToolCallback callback = callbackByName.get(toolName);
                String result;
                if (cancellationRequested) {
                    result = "工具调用未执行：用户已请求停止";
                } else {
                    ReActToolContext.ToolCallDecision decision = context == null
                            ? ReActToolContext.ToolCallDecision.allow(toolName)
                            : context.admitToolCall(toolName, toolArguments, callback != null,
                            context.getAllowedTools() != null && context.getAllowedTools().contains(toolName));
                    if (!decision.allowed()) {
                        int guardStep = context == null ? 0 : Math.max(0, context.consumeStep());
                        result = "[" + decision.code() + "] " + decision.message();
                        sendToolGuardrail(context, guardStep, decision.code(), result);
                        recordToolResult(context, toolCall, result);
                        return result;
                    }
                    try {
                        result = callback.call(toolArguments);
                    } catch (Exception e) {
                        if (e instanceof SubagentCancellationException
                                || e instanceof java.util.concurrent.CancellationException
                                || Thread.currentThread().isInterrupted()) {
                            cancellationRequested = true;
                            result = "工具调用已完成当前边界：用户已请求停止";
                        } else {
                            result = "工具执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        }
                    }
                }
                recordToolResult(context, toolCall, result);
                if (isToolFailureResult(result)) {
                    consecutiveToolFailures++;
                } else {
                    consecutiveToolFailures = 0;
                }
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), result == null ? "" : result));
                if (consecutiveToolFailures >= 2) {
                    return "工具连续失败 2 次，已停止继续调用。请检查工具配置、服务状态或输入参数。";
                }
            }
            for (ToolResponseMessage.ToolResponse responseItem : toolResponses) {
                conversationMaps.add(HistoryMessageMapper.toolMap(
                        responseItem.id(), responseItem.name(), responseItem.responseData()));
            }
            if (cancellationRequested) {
                throw new java.util.concurrent.CancellationException("ReAct cancellation requested");
            }
        }
        return "ReAct 已达到模型推理轮数上限（" + boundedMaxSteps + "），已停止继续调用工具。";
    }

    private void sendToolGuardrail(ReActToolContext context, int step, String code, String content) {
        if (context == null || context.getEmitter() == null) return;
        try {
            context.getEmitter().send("data: " + JSON.toJSONString(
                    ReActExecuteResultEntity.createToolGuardrail(code, step, content, context.getSessionId())) + "\n\n");
        } catch (Exception e) {
            log.debug("发送工具防护反馈失败: {}", e.getMessage());
        }
    }

    private void recordToolResult(ReActToolContext context, AssistantMessage.ToolCall toolCall, String result) {
        if (context == null || toolCall == null) return;
        try {
            messageRecorder.recordTool(context.getSessionId(), context.getAgentId(), 0,
                    context.getCurrentStep().get(), toolCall.id(), toolCall.name(), toolCall.arguments(),
                    result == null ? "" : result);
        } catch (Exception e) {
            log.warn("记录工具调用结果失败: tool={}, reason={}", toolCall.name(), e.getMessage());
        }
    }

    private String toolDescription(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) return "";
        StringBuilder description = new StringBuilder();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) continue;
            description.append(callback.getToolDefinition().name()).append(':')
                    .append(callback.getToolDefinition().description()).append('\n');
        }
        return description.toString();
    }

    public static boolean isToolFailureResult(String result) {
        if (result == null || result.isBlank()) return true;
        String normalized = result.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("工具执行失败")
                || normalized.contains("工具调用已拦截")
                || normalized.contains("unknown_tool")
                || normalized.contains("unauthorized_tool")
                || normalized.contains("tool_frequency")
                || normalized.contains("mcp 调用异常")
                || normalized.contains("mcp 客户端未就绪")
                || normalized.contains("tool execution failed")
                || normalized.contains("connection refused")
                || normalized.contains("unknown tool")
                || normalized.contains("未知工具");
    }

    private Object callModelWithRetry(ChatModel chatModel, List<Message> messages,
                                            ToolCallingChatOptions options, String modelId) {
        for (int i = 0; i <= 2; i++) {
            try {
                if (i > 0) {
                    log.warn("模型调用重试 #{}", i);
                    Thread.sleep(1000L);
                }
                ChatResponse result = chatModel.call(new Prompt(messages, options));
                if (result != null && result.getResult() != null) return result;
            } catch (Exception e) {
                if (e instanceof java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("ReAct 模型调用已取消");
                }
                log.error("模型调用失败(#{}): {}", i, e.getMessage());
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (isServiceUnavailableError(message)) {
                    return "模型服务暂时繁忙（HTTP 503），已停止重复重试。请切换到 2001（DeepSeek Chat）或 2002（SenseNova），也可以稍后重试。";
                }
                if (isRateLimitError(message)) {
                    return "模型接口当前已限流（429），请稍后重试或更换模型/API Key。";
                }
                if (message.contains("404") || message.toLowerCase().contains("model is not found")) {
                    return "模型调用失败：模型 " + modelId + " 在当前接口中不存在，请检查数据库里的 modelName。";
                }
            }
        }
        String fallback = "模型调用失败：请检查模型名称、接口地址和 API Key。";
        log.error("模型调用多次失败，使用 fallback 回复");
        return fallback;
    }

    private Object[] selectToolObjects(List<String> allowedTools) {
        List<Object> tools = new java.util.ArrayList<>();
        if (allowedTools.contains(ReActToolAllowlistPolicy.READ_FILE)) tools.add(fileReadTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.WRITE_FILE)) tools.add(fileWriteTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.RUN_BASH)) tools.add(bashTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.DISCOVER_MCP_TOOLS)) tools.add(mcpToolDiscoveryTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.GET_MCP_TOOL_SCHEMA)) tools.add(mcpToolSchemaTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) tools.add(mcpToolHandleCallTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL)) tools.add(retrieveToolCallTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_CASES)) tools.add(queryCaseTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_FEEDBACK)) tools.add(queryFeedbackTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.TASK)) tools.add(subagentTaskTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS)
                || allowedTools.contains(ReActToolAllowlistPolicy.TASK)) tools.add(dispatchSubagentsTool);
        return tools.toArray();
    }

    /** 构建动态系统提示词：soul + 授权工具说明 + 绑定 skills + 绑定 MCP（仅名+描述） */
    private String buildSystemPrompt(AgentRuntimeBindingService.AgentRuntimeBindings bindings,
                                     List<String> allowedTools,
                                     List<String> explicitToolIds) {
        StringBuilder sb = new StringBuilder();
        AiAgentVO agent = bindings.getAgent();
        List<String> boundSkillIds = bindings.getSkillIds();
        List<String> boundMcpIds = bindings.getMcpIds();

        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sb.append(agent.getSystemPrompt()).append("\n\n");
        }

        // 这段规则必须使用可靠的 UTF-8 文本，避免历史乱码提示词让模型误以为没有数据访问能力。
        sb.append("""
                【运行时工具规则】
                1. 只可以调用下方列出的、当前 Agent 已绑定并通过运行时校验的工具；没有列出的能力一律不可假设存在。
                2. 用户只是描述业务问题时，记录 Feedback，不要读取项目目录、代码或运行命令。
                3. 用户明确要求“查询/查看/拉取/获取今日（今天）的反馈”时，这是只读查询任务：如果已绑定 MCP，先调用 discover_mcp_tools 获取候选工具和完整参数 Schema，再调用 call_mcp_tool；不要询问生产授权，不要回复“无法访问生产数据”。
                4. 工具发现必须使用当前 Agent 已绑定的 mcpId；discover_mcp_tools 返回的 toolHandle、inputSchema 和 schemaHash 是本次会话的唯一调用依据，不要猜测工具名或参数。
                5. 如果工具发现没有返回匹配的反馈查询工具，必须明确报告“当前 Agent 未发现可用的库存反馈工具”，禁止改用未发现的 search_feedback、query_feedback 或其他模糊搜索工具伪造今日结果。
                6. 工具返回结果后，用中文按优先级、来源、业务服务和数量汇总；不要再次把同一查询交给 Subagent。
                7. 用户明确要求“分诊/评测/结合业务 Skill/巡检”时，先读取已绑定 Skill，再调用对应 MCP 获取事实，自动输出分类、优先级、证据充分性、缺失信息和候选 Case 结论；不要为只读查询或评测过程请求人工授权。
                8. 只有用户明确要求“升级/发布/确认 Case”时才调用 promote_feedback_to_case；自动评测不得声称 Case 已发布，人工审核边界必须保持 PENDING_REVIEW。
                9. 读取反馈、分诊和评测时，不得读取项目代码、运行 Bash 或调用未绑定工具；只有用户明确要求排查代码/运行命令时才允许这样做。
                10. 工具调用由服务端按滑动窗口校验：同工具同参数重复调用会被拦截，同一工具短窗口内过于频繁也会被拦截；收到工具防护反馈后必须停止重复尝试并调整方案。

                """);
        if (allowedTools.contains(ReActToolAllowlistPolicy.DISCOVER_MCP_TOOLS)
                && boundMcpIds != null && !boundMcpIds.isEmpty()) {
            sb.append("【已绑定 MCP 服务（工具按需发现）】\n");
            for (var mcp : bindings.getMcpTools()) {
                sb.append("- mcpId=").append(mcp.getMcpId())
                        .append(": ").append(mcp.getMcpName())
                        .append(" (").append(mcp.getTransportType()).append(")\n");
            }
            sb.append("先调用 discover_mcp_tools(query, mcpId?, limit=3) 按用户意图检索工具；结果会返回候选工具的完整 inputSchema、schemaHash 和会话级 toolHandle。随后只能调用 call_mcp_tool(toolHandle, args)，不要把 mcpId/toolName 当作新句柄。\n");
        }

        sb.append("""
                能力边界：
                - 只能使用系统提示词中明确列出的工具，不能编造 Bash、ReadFile、WriteFile、Python、MySQL、Redis、SearchFile 等未授权工具。
                - Skills 是当前 Agent 绑定的业务技能包；MCP 是当前 Agent 绑定的外部服务能力；二者不能混同。
                - 用户只是反馈问题时，先记录/确认反馈，不要主动排查项目、读取文件或运行命令。
                - 当用户询问“你有什么 skills / MCP / 工具”时，只能回答本提示词中明确列出的绑定清单；不要猜测、不要引用全局 demo skill、不要把未绑定能力说成已可用。

                可用工具：
                """);
        if (allowedTools == null || allowedTools.isEmpty()) {
            sb.append("- 无。当前 Agent 没有绑定任何可调用工具；如果用户询问工具/技能，请如实说明当前没有可调用配置。\n");
        }
        boolean explicitReadFile = explicitToolIds != null && explicitToolIds.contains(ReActToolAllowlistPolicy.READ_FILE);
        if (allowedTools.contains(ReActToolAllowlistPolicy.READ_FILE)) {
            if (explicitReadFile) {
                sb.append("- read_file(relativePath): 读取工作目录下指定相对路径的文本文件\n");
            } else {
                sb.append("- read_file(relativePath): 仅可读取已绑定 Skill 的虚拟路径 .ma/skills/{skillId}/...，不可读取项目代码或其他目录\n");
            }
        }
        if (allowedTools.contains(ReActToolAllowlistPolicy.WRITE_FILE)) sb.append("- write_file(relativePath, content): 在工作目录下写入或覆盖文本文件\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.RUN_BASH)) sb.append("- run_bash(command): 在工作目录内执行一条白名单内的 shell 命令\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.DISCOVER_MCP_TOOLS)) sb.append("- discover_mcp_tools(query, mcpId?, limit?): 按用户意图在当前 Agent 绑定的 MCP 中检索最多 3 个工具，并返回完整 inputSchema 与会话级 toolHandle\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) sb.append("- call_mcp_tool(toolHandle, args): 使用 discover_mcp_tools 返回的会话级句柄调用 MCP 工具；句柄失效时重新发现，不得直接猜测参数\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL)) sb.append("- retrieve_tool_call(toolCallId): 按 ID 取回被折叠/压缩的完整消息原文\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_CASES)) sb.append("- query_cases(keyword, limit): 查询 Case 案例库，用户问历史问题或案例时调用\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_FEEDBACK)) sb.append("- query_feedback(limit, agentId): 查询用户反馈，用户问最近反馈时调用\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.TASK)) sb.append("- task(description, prompt): 将单个复杂独立任务交给一级 Subagent（串行），等待其结果后再汇总\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS)) sb.append("- dispatch_subagents(tasksJson): 把多个相互独立、可并行的子任务一次性提交，最多并行 3 个并聚合结果；tasksJson 形如 [{\"description\":\"查A\",\"prompt\":\"查询A\"}]\n");

        if (allowedTools.contains(ReActToolAllowlistPolicy.TASK)) {
            sb.append("\nSubagent routing rule: use dispatch_subagents for two or more independent tasks; use task only for one task. dispatch_subagents runs at most 3 child agents in parallel.\n");
        }
        if (boundSkillIds != null && !boundSkillIds.isEmpty()) {
            sb.append("\n该 Agent 绑定的 Skills（需要时直接使用 read_file 读取）：\n");
            for (String sid : boundSkillIds) {
                var skill = bindings.getSkillMetadataById().get(sid);
                if (skill != null) {
                    sb.append("- ").append(sid).append(": ").append(skill.getSkillName())
                            .append(" - ").append(skill.getDescription()).append("\n");
                }
                sb.append("  虚拟路径：.ma/skills/").append(sid).append("/SKILL.md\n");
            }
            sb.append("\n当用户请求匹配某个 Skill 时，先使用 read_file 读取对应的 .ma/skills/{skillId}/SKILL.md；按手册需要再读取同目录下的脚本和参考文件。不要扫描或读取未绑定的 Skill。若用户只是询问“当前有哪些 Skills”，直接基于上面的绑定清单回答，不要再自行搜索项目目录。\n");
        } else {
            sb.append("\n该 Agent 当前没有绑定可执行 Skills。不要声称存在 demo skill、项目扫描 skill 或其他技能。\n");
        }

        if (allowedTools.contains(ReActToolAllowlistPolicy.DISCOVER_MCP_TOOLS) && boundMcpIds != null && !boundMcpIds.isEmpty()) {
            var mcps = bindings.getMcpTools();
            if (!mcps.isEmpty()) {
                sb.append("\n该 Agent 绑定的 MCP 服务（工具需要按意图发现）：\n");
                for (var m : mcps) {
                    sb.append("- ").append(m.getMcpId()).append(": ").append(m.getMcpName())
                            .append(" (").append(m.getTransportType()).append(")\n");
                }
                sb.append("""
                    使用方式：先 discover_mcp_tools(query="用户意图", mcpId="可选的服务ID", limit=3)，再使用返回的 toolHandle 调用 call_mcp_tool(toolHandle="句柄", args="{"参数名":"参数值"}")。
                    参数必须严格符合发现结果中的 inputSchema；若返回 MCP_TOOL_HANDLE_EXPIRED，重新发现后再调用。
                    如果用户只是询问“当前有哪些 MCP”，直接依据上述绑定清单回答，不要自行假设还有数据库、Redis、搜索等外部能力。
                    """);
            }
        } else {
            sb.append("\n该 Agent 当前没有绑定 MCP。不要声称存在 MySQL、Redis、搜索、文件等 MCP 能力。\n");
        }

        return sb.toString();
    }
}
