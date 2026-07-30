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
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
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
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpCallTool;
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
    private McpCallTool mcpCallTool;

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
                .agentId(agentId)
                .emitter(emitter)
                .workDir(workDir)
                .boundSkillIds(skillIds)
                .boundMcpIds(mcpIds)
                .build());

        fileReadTool.resetStep();
        fileWriteTool.resetStep();
        bashTool.resetStep();
        mcpCallTool.resetStep();

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
            List<String> allowedTools = bindings.getEffectiveToolIds();
            String currentExecutionId = executionId;
            ReActToolContextHolder.set(ReActToolContext.builder()
                    .sessionId(sessionId).agentId(agentId).emitter(emitter).workDir(workDir)
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
            messageRecorder.recordUser(sessionId, agentId, 0, requestParameter.getMessage());

            // 从 DB 加载历史（仅 user/assistant 纯文本，无 tool 中间态）
            List<HistoryMessage> history = messageRecorder.getHistory(sessionId);

            // 消息 Map 列表 → fold 管线
            java.util.List<java.util.Map<String, Object>> msgMaps = new java.util.ArrayList<>();
            for (HistoryMessage h : history) {
                java.util.Map<String, Object> message = new java.util.LinkedHashMap<>();
                message.put("role", h.getRole());
                message.put("content", h.getContent());
                msgMaps.add(message);
            }
            java.util.Map<String, Object> currentMessage = new java.util.LinkedHashMap<>();
            currentMessage.put("role", "user");
            currentMessage.put("content", requestParameter.getMessage());
            msgMaps.add(currentMessage);
            msgMaps = MemoryFoldingPipeline.fold(msgMaps);

            // 转 Spring AI Message
            List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> m : msgMaps) {
                String r = (String) m.get("role");
                String c = (String) m.get("content");
                if (c == null) c = "";
                if ("user".equals(r)) msgs.add(new UserMessage(c));
                else if ("assistant".equals(r)) msgs.add(new AssistantMessage(c));
            }

            String finalContent = callReActLoop(chatModel, systemPrompt, msgs,
                    internalTools.getToolCallbacks(), selectedModelId, maxSteps);
            ReActToolContext executionContext = ReActToolContextHolder.get();
            int usedSteps = executionContext == null ? 0 : executionContext.getCurrentStep().get();
            executionRepository.updateProgress(executionId, 0, usedSteps,
                    JSON.toJSONString(Map.of("toolSteps", usedSteps)));
            sendStateEvent(emitter, executionId, sessionId, 0, usedSteps, "RUNNING");

            // 记录 LLM 调用日志
            try {
                ChatMessageRecorder.LlmLogEntry logEntry = ChatMessageRecorder.LlmLogEntry.builder()
                        .sessionId(sessionId).agentId(agentId)
                        .modelName(selectedModelId)
                        .mode(AiAgentModeEnum.REACT.getCode())
                        .durationMs(0).status("success")
                        .historyMsgCount(history.size())
                        .foldedMsgCount(msgMaps.size())
                        .systemPromptLen(systemPrompt.length())
                        .userMessageLen(requestParameter.getMessage().length())
                        .assistantResponseLen(finalContent != null ? finalContent.length() : 0)
                        .build();
                messageRecorder.recordLlmLog(logEntry);
            } catch (Exception ignored) {}

            if (finalContent == null || finalContent.isBlank()) {
                finalContent = "未能生成有效回复";
            }

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
                                 List<Message> messages, ToolCallback[] callbacks,
                                 String modelId, int maxSteps) {
        Map<String, ToolCallback> callbackByName = new java.util.HashMap<>();
        for (ToolCallback callback : callbacks) {
            callbackByName.put(callback.getToolDefinition().name(), callback);
        }
        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(systemPrompt));
        conversation.addAll(messages);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(java.util.Arrays.asList(callbacks))
                .build();

        int boundedMaxSteps = Math.max(1, maxSteps);
        for (int round = 0; round < boundedMaxSteps; round++) {
            ReActToolContext context = ReActToolContextHolder.get();
            if (context != null && context.isCancellationRequested()) {
                throw new java.util.concurrent.CancellationException("ReAct cancellation requested");
            }
            Object modelResult = callModelWithRetry(chatModel, conversation, options, modelId);
            if (modelResult instanceof String text) return text;
            ChatResponse response = (ChatResponse) modelResult;
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return "模型未返回有效响应";
            }
            AssistantMessage assistant = response.getResult().getOutput();
            conversation.add(assistant);
            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) return assistant.getText();

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            boolean cancellationRequested = false;
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                context = ReActToolContextHolder.get();
                if (context != null && context.isCancellationRequested()) {
                    cancellationRequested = true;
                }
                ToolCallback callback = callbackByName.get(toolCall.name());
                String result;
                if (cancellationRequested) {
                    result = "工具调用未执行：用户已请求停止";
                } else if (callback == null) {
                    result = "未授权的工具: " + toolCall.name();
                } else {
                    try {
                        result = callback.call(toolCall.arguments());
                    } catch (Exception e) {
                        if (e instanceof SubagentCancellationException
                                || e instanceof java.util.concurrent.CancellationException
                                || Thread.currentThread().isInterrupted()) {
                            cancellationRequested = true;
                            result = "工具调用已完成当前边界：用户已请求停止";
                        }
                        result = "工具执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                }
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), result == null ? "" : result));
            }
            conversation.add(new ToolResponseMessage(toolResponses));
            if (cancellationRequested) {
                throw new java.util.concurrent.CancellationException("ReAct cancellation requested");
            }
        }
        return "已达到 ReAct 步数上限（" + boundedMaxSteps + "）";
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
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) tools.add(mcpCallTool);
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
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) sb.append("- call_mcp_tool(mcpId, toolName, args): 调用一个绑定的 MCP 工具\n");
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

        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL) && boundMcpIds != null && !boundMcpIds.isEmpty()) {
            var mcps = bindings.getMcpTools();
            if (!mcps.isEmpty()) {
                sb.append("\n该 Agent 绑定的 MCP 工具（可通过 call_mcp_tool 调用）：\n");
                for (var m : mcps) {
                    sb.append("- ").append(m.getMcpId()).append(": ").append(m.getMcpName())
                            .append(" (").append(m.getTransportType()).append(")\n");
                }
                sb.append("""
                    使用方式：call_mcp_tool(mcpId="工具ID", toolName="工具内具体方法名", args="{"参数名":"参数值"}")
                    调用前请确认参数格式正确。
                    如果用户只是询问“当前有哪些 MCP”，直接依据上述绑定清单回答，不要自行假设还有数据库、Redis、搜索等外部能力。
                    """);
            }
        } else {
            sb.append("\n该 Agent 当前没有绑定 MCP。不要声称存在 MySQL、Redis、搜索、文件等 MCP 能力。\n");
        }

        return sb.toString();
    }
}
