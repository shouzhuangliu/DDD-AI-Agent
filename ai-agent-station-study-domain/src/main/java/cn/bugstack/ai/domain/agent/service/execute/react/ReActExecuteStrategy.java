package cn.bugstack.ai.domain.agent.service.execute.react;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentModeEnum;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessage;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileReadTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileWriteTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.BashTool;
import cn.bugstack.ai.domain.agent.service.tools.skill.SkillExecuteTool;
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpCallTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.RetrieveToolCallTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryCaseTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryFeedbackTool;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.file.Path;
import java.util.List;

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
    private ReActToolProperties properties;

    @Resource
    private FileReadTool fileReadTool;

    @Resource
    private FileWriteTool fileWriteTool;

    @Resource
    private BashTool bashTool;

    @Resource
    private SkillExecuteTool skillExecuteTool;

    @Resource
    private McpCallTool mcpCallTool;

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
    private ChatMessageRecorder messageRecorder;

    @Resource
    private AgentWorkspaceService agentWorkspaceService;

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

        List<String> skillIds = repository.queryBoundSkillIds(agentId);
        List<String> mcpIds = repository.queryBoundMcpIds(agentId);
        Path workDir = agentWorkspaceService.syncSkills(agentId, agent.getWorkDir(), properties.getWorkDir(), skillIds);

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
        skillExecuteTool.resetStep();
        mcpCallTool.resetStep();

        try {
            String selectedModelId = ModelSelectionService.select(requestParameter.getModelId(), agent.getModelId());
            String modelBeanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(selectedModelId);
            OpenAiChatModel chatModel = applicationContext.getBean(modelBeanName, OpenAiChatModel.class);
            log.info("ReAct 使用模型，agentId={}，sessionId={}，modelId={}", agentId, sessionId, selectedModelId);

            List<String> allowedTools = resolveRuntimeTools(
                    toolAllowlistPolicy.resolve(repository.queryBoundToolIds(agentId)), skillIds, mcpIds);

            String systemPrompt = buildSystemPrompt(agent, skillIds, mcpIds, allowedTools);

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
                msgMaps.add(java.util.Map.of("role", h.getRole(), "content", h.getContent()));
            }
            msgMaps.add(java.util.Map.of("role", "user", "content", requestParameter.getMessage()));
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

            org.springframework.ai.chat.client.ChatClient chatClient =
                    org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                            .defaultSystem(systemPrompt)
                            .defaultToolCallbacks(internalTools)
                            .build();

            String finalContent = callWithRetry(chatClient, msgs);

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

            emitter.send("data: " + JSON.toJSONString(
                    ReActExecuteResultEntity.createFinal(finalContent, sessionId)) + "\n\n");
            emitter.send("data: " + JSON.toJSONString(
                    ReActExecuteResultEntity.createComplete(sessionId)) + "\n\n");

        } catch (Exception e) {
            log.error("ReAct 执行异常: {}", e.getMessage(), e);
            try {
                emitter.send("data: " + JSON.toJSONString(
                        ReActExecuteResultEntity.createError(e.getMessage(), sessionId)) + "\n\n");
            } catch (Exception ignored) {
            }
        } finally {
            ReActToolContextHolder.clear();
        }
    }

    /**
     * 带重试的模型调用。失败后等 1s 重试,最多 2 次,仍失败则返回 fallback。
     */
    private String callWithRetry(org.springframework.ai.chat.client.ChatClient client, List<org.springframework.ai.chat.messages.Message> messages) {
        String[] retries = {"", ""};
        for (int i = 0; i <= 2; i++) {
            try {
                if (i > 0) {
                    log.warn("模型调用重试 #{}", i);
                    Thread.sleep(1000L);
                }
                String result = client.prompt().messages(messages).call().content();
                if (result != null && !result.isBlank()) {
                    return result;
                }
            } catch (Exception e) {
                log.error("模型调用失败(#{}): {}", i, e.getMessage());
            }
        }
        String fallback = "抱歉，暂时无法处理您的请求，请稍后重试。";
        log.error("模型调用多次失败，使用 fallback 回复");
        return fallback;
    }

    private Object[] selectToolObjects(List<String> allowedTools) {
        List<Object> tools = new java.util.ArrayList<>();
        if (allowedTools.contains(ReActToolAllowlistPolicy.READ_FILE)) tools.add(fileReadTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.WRITE_FILE)) tools.add(fileWriteTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.RUN_BASH)) tools.add(bashTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.EXECUTE_SKILL)) tools.add(skillExecuteTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) tools.add(mcpCallTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL)) tools.add(retrieveToolCallTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_CASES)) tools.add(queryCaseTool);
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_FEEDBACK)) tools.add(queryFeedbackTool);
        return tools.toArray();
    }

    private List<String> resolveRuntimeTools(List<String> explicitlyAllowedTools, List<String> boundSkillIds, List<String> boundMcpIds) {
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>(
                explicitlyAllowedTools == null ? List.of() : explicitlyAllowedTools);
        boolean hasSkills = boundSkillIds != null && !boundSkillIds.isEmpty();
        boolean hasMcps = boundMcpIds != null && !boundMcpIds.isEmpty();
        if (hasSkills) {
            tools.add(ReActToolAllowlistPolicy.EXECUTE_SKILL);
        } else {
            tools.remove(ReActToolAllowlistPolicy.EXECUTE_SKILL);
        }
        if (hasMcps) {
            tools.add(ReActToolAllowlistPolicy.CALL_MCP_TOOL);
        } else {
            tools.remove(ReActToolAllowlistPolicy.CALL_MCP_TOOL);
        }
        return new java.util.ArrayList<>(tools);
    }

    /** 构建动态系统提示词：soul + 授权工具说明 + 绑定 skills + 绑定 MCP（仅名+描述） */
    private String buildSystemPrompt(AiAgentVO agent, List<String> boundSkillIds, List<String> boundMcpIds, List<String> allowedTools) {
        StringBuilder sb = new StringBuilder();

        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sb.append(agent.getSystemPrompt()).append("\n\n");
        }

        sb.append("""
                能力边界：
                - 只能使用系统提示词中明确列出的工具，不能编造 Bash、ReadFile、WriteFile、Python、MySQL、Redis、SearchFile 等未授权工具。
                - Skills 是当前 Agent 绑定的业务技能包；MCP 是当前 Agent 绑定的外部服务能力；二者不能混同。
                - 用户只是反馈问题时，先记录/确认反馈，不要主动排查项目、读取文件或运行命令。

                可用工具：
                """);
        if (allowedTools == null || allowedTools.isEmpty()) {
            sb.append("- 无。当前 Agent 没有绑定任何可调用工具；如果用户询问工具/技能，请如实说明当前没有可调用配置。\n");
        }
        if (allowedTools.contains(ReActToolAllowlistPolicy.READ_FILE)) sb.append("- read_file(relativePath): 读取工作目录下指定相对路径的文本文件\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.WRITE_FILE)) sb.append("- write_file(relativePath, content): 在工作目录下写入或覆盖文本文件\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.RUN_BASH)) sb.append("- run_bash(command): 在工作目录内执行一条白名单内的 shell 命令\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.EXECUTE_SKILL)) sb.append("- execute_skill(skillId): 执行一个已注册的 Skill，返回操作手册（SKILL.md）\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL)) sb.append("- call_mcp_tool(mcpId, toolName, args): 调用一个绑定的 MCP 工具\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.RETRIEVE_TOOL_CALL)) sb.append("- retrieve_tool_call(toolCallId): 按 ID 取回被折叠/压缩的完整消息原文\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_CASES)) sb.append("- query_cases(keyword, limit): 查询 Case 案例库，用户问历史问题或案例时调用\n");
        if (allowedTools.contains(ReActToolAllowlistPolicy.QUERY_FEEDBACK)) sb.append("- query_feedback(limit, agentId): 查询用户反馈，用户问最近反馈时调用\n");

        if (allowedTools.contains(ReActToolAllowlistPolicy.EXECUTE_SKILL) && boundSkillIds != null && !boundSkillIds.isEmpty()) {
            sb.append("\n该 Agent 绑定的 Skills（可使用 execute_skill 工具执行）：\n");
            for (String sid : boundSkillIds) {
                var skill = skillScannerService.readSkillFromWorkDir(workDirStringForPrompt(agent), sid);
                if (skill != null) {
                    sb.append("- ").append(sid).append(": ").append(skill.getSkillName())
                            .append(" — ").append(skill.getDescription()).append("\n");
                }
                sb.append("  虚拟路径：.ma/skills/").append(sid).append("/SKILL.md\n");
            }
            sb.append("\n当用户请求的任务可以通过某个 Skill 完成时，请优先调用 execute_skill 工具获取操作手册；若需要读取附件，使用 read_file 读取 .ma/skills/{skillId}/ 下的文件。\n");
        } else {
            sb.append("\n该 Agent 当前没有绑定可执行 Skills。不要声称存在 demo skill、项目扫描 skill 或其他技能。\n");
        }

        // MCP 工具仅列名称+描述（不挂全量 schema，防上下文膨胀）
        if (allowedTools.contains(ReActToolAllowlistPolicy.CALL_MCP_TOOL) && boundMcpIds != null && !boundMcpIds.isEmpty()) {
            var mcps = repository.queryMcpToolsByIds(boundMcpIds);
            if (!mcps.isEmpty()) {
                sb.append("\n该 Agent 绑定的 MCP 工具（可通过 call_mcp_tool 调用）：\n");
                for (var m : mcps) {
                    sb.append("- ").append(m.getMcpId()).append(": ").append(m.getMcpName())
                            .append(" (").append(m.getTransportType()).append(")\n");
                }
                sb.append("""
                    使用方式：call_mcp_tool(mcpId="工具ID", toolName="工具内具体方法名", args="{"参数名":"参数值"}")
                    调用前请确认参数格式正确。
                    """);
            }
        } else {
            sb.append("\n该 Agent 当前没有绑定 MCP。不要声称存在 MySQL、Redis、搜索、文件等 MCP 能力。\n");
        }

        return sb.toString();
    }

    private String workDirStringForPrompt(AiAgentVO agent) {
        return agentWorkspaceService.resolveWorkDir(agent.getAgentId(), agent.getWorkDir(), properties.getWorkDir()).toString();
    }
}
