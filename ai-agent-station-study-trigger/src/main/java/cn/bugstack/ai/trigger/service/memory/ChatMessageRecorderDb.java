package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessage;
import cn.bugstack.ai.domain.agent.service.memory.ToolCallExchange;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import cn.bugstack.ai.infrastructure.dao.po.MemoryState;
import cn.bugstack.ai.infrastructure.dao.po.MemoryToolResult;
import cn.bugstack.ai.trigger.service.analysis.AnalysisJobQueue;
import cn.bugstack.ai.trigger.service.agent.AgentBusinessContextService;
import cn.bugstack.ai.trigger.service.feedback.FeedbackAdmissionPolicy;
import cn.bugstack.ai.trigger.service.feedback.McpFeedbackIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Primary
@Component
public class ChatMessageRecorderDb implements ChatMessageRecorder {

    @Resource private IChatMessageDao chatMessageDao;
    @Resource private IAiLlmLogDao llmLogDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private AnalysisJobQueue analysisJobQueue;
    @Resource private IMemorySummaryDao memorySummaryDao;
    @Resource private IMemoryStateDao memoryStateDao;
    @Resource private IMemoryToolResultDao memoryToolResultDao;
    @Resource private IAiSessionDao sessionDao;
    @Resource private LongTermMemoryPort longTermMemoryPort;
    @Resource private MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;
    @Resource private FeedbackAdmissionPolicy feedbackAdmissionPolicy;
    @Resource private McpFeedbackIngestionService mcpFeedbackIngestionService;
    @Resource private AgentBusinessContextService agentBusinessContextService;

    @Override public void recordUser(String s, String a, int t, String c) { save(s, a, t, 0, "user", c, null, null, null, null); }
    @Override public void recordAssistant(String s, String a, int t, int st, String c, String tc) {
        ChatMessage saved = save(s, a, t, st, "assistant", c, null, null, null, tc);
        if (isInternalExecutionPlaceholder(c)) return;
        String latestUser = chatMessageDao.queryBySessionId(s).stream()
                .filter(message -> "user".equalsIgnoreCase(message.getRole()))
                .reduce((ignored, latest) -> latest)
                .map(ChatMessage::getContent).orElse("");
        boolean immediate = feedbackAdmissionPolicy.shouldCapture(
                latestUser, agentBusinessContextService.collectKeywords(a));
        try { analysisJobQueue.enqueue(a, s, saved.getId(), immediate); }
        catch (Exception exception) { log.warn("Failed to enqueue conversation analysis for message {}", saved.getId(), exception); }
    }
    @Override public void recordTool(String s, String a, int t, int st, String id, String n, String args, String c) {
        ChatMessage saved = save(s, a, t, st, "tool", c, id, n, args, null);
        String toolContent = c == null ? "" : c;
        String normalized = toolContent.toLowerCase();
        boolean failed = normalized.contains("error") || normalized.contains("工具调用已拦截")
                || normalized.contains("工具执行失败") || normalized.contains("mcp 调用异常")
                || normalized.contains("未知工具") || normalized.contains("未授权工具");
        memoryToolResultDao.insertIgnore(MemoryToolResult.builder().agentId(a).sessionId(s).messageId(saved.getId())
                .toolName(n == null ? "" : n).conclusion(MemoryFoldingPipeline.foldPlainText(c == null ? "" : c))
                .keyParametersJson(args == null ? "{}" : args)
                .errorSummary(failed ? MemoryFoldingPipeline.foldPlainText(toolContent) : "")
                .createdAt(LocalDateTime.now()).build());
        try {
            mcpFeedbackIngestionService.ingest(a, s, saved.getId(), n, args, c);
        } catch (Exception exception) {
            // MCP 同步是旁路能力，不能因为外部反馈格式异常阻断对话记录。
            log.warn("MCP 业务反馈同步失败 agentId={}, tool={}", a, n, exception);
        }
    }

    private ChatMessage save(String s, String a, int t, int st, String r, String c, String tid, String tn, String ta, String tcj) {
        ChatMessage message = ChatMessage.builder().sessionId(s).agentId(a).turn(t).step(st).role(r).content(c)
                .toolCallId(tid != null ? tid : "").toolName(tn != null ? tn : "")
                .toolArguments(ta != null ? ta : "").toolCallsJson(tcj != null ? tcj : "")
                .compressed(0).createdAt(LocalDateTime.now()).build();
        chatMessageDao.insert(message);
        sessionDao.touch(s, preview(c), "");
        return message;
    }

    private static String preview(String value) {
        String plain = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return plain.substring(0, Math.min(plain.length(), 500));
    }

    private static boolean isInternalExecutionPlaceholder(String value) {
        return value != null && value.toLowerCase().contains("ai agent execution summary completed!");
    }

    @Override public List<HistoryMessage> getHistory(String sessionId) {
        List<ChatMessage> all = chatMessageDao.queryBySessionId(sessionId);
        List<HistoryMessage> r = new ArrayList<>();
        MemorySummary summary = memorySummaryDao.queryLatest(sessionId);
        long covered = summary == null ? 0 : summary.getEndMessageId();
        if (summary != null) {
            MemoryState state = memoryStateDao.queryLatest(sessionId);
            String stateText = state == null ? "" : "\n会话状态: goals=" + state.getGoalsJson()
                    + ", constraints=" + state.getConstraintsJson() + ", pending=" + state.getPendingJson();
            r.add(HistoryMessage.builder().role("assistant").content("[滚动会话摘要]\n" + summary.getSummary() + stateText).build());
        }
        String agentId = all.isEmpty() ? "" : all.get(0).getAgentId();
        String latestUserInput = all.stream().filter(message -> "user".equals(message.getRole()))
                .reduce((ignored, latest) -> latest).map(ChatMessage::getContent).orElse("");
        if (memoryQueryAdmissionPolicy.shouldRecall(latestUserInput)) {
            longTermMemoryPort.retrieve(agentId, agentId, latestUserInput, 3).forEach(memory ->
                    r.add(HistoryMessage.builder().role("assistant").content("[跨会话长期记忆] " + memory.content()).build()));
        }
        for (ChatMessage m : all) {
            if (m.getId() > covered && ("user".equals(m.getRole()) || "assistant".equals(m.getRole())))
                r.add(HistoryMessage.builder().role(m.getRole()).content(m.getContent()).build());
        }
        return r;
    }

    @Override public String findByToolCallId(String id) {
        ChatMessage m = chatMessageDao.queryByToolCallId(id);
        return m != null ? m.getContent() : null;
    }

    @Override public ToolCallExchange findToolExchange(String sessionId, String toolCallId) {
        if (sessionId == null || sessionId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        ChatMessage toolMessage = chatMessageDao.queryBySessionAndToolCallId(sessionId, toolCallId);
        if (toolMessage == null) return null;

        ChatMessage assistantMessage = chatMessageDao.queryBySessionId(sessionId).stream()
                .filter(message -> message.getId() != null && toolMessage.getId() != null
                        && message.getId() < toolMessage.getId())
                .filter(message -> "assistant".equalsIgnoreCase(message.getRole()))
                .filter(message -> message.getToolCallsJson() != null
                        && message.getToolCallsJson().contains(toolCallId))
                .reduce((ignored, latest) -> latest)
                .orElse(null);
        return new ToolCallExchange(sessionId, toolCallId,
                toolMessage.getToolName(), toolMessage.getToolArguments(),
                assistantMessage == null ? "" : assistantMessage.getContent(),
                toolMessage.getContent());
    }
    @Override public void markCompressed(long id) { chatMessageDao.updateCompressed(id, 1); }

    @Override public void recordLlmLog(LlmLogEntry e) {
        llmLogDao.insert(AiLlmLog.builder().sessionId(e.getSessionId()).agentId(e.getAgentId())
                .modelName(e.getModelName()).mode(e.getMode()).durationMs(e.getDurationMs())
                .status(e.getStatus()).errorMessage(e.getErrorMessage())
                .historyMsgCount(e.getHistoryMsgCount()).foldedMsgCount(e.getFoldedMsgCount())
                .systemPromptLen(e.getSystemPromptLen()).userMessageLen(e.getUserMessageLen())
                .assistantResponseLen(e.getAssistantResponseLen())
                .createdAt(LocalDateTime.now()).build());
    }
    @Override public List<?> queryLlmLogs(int limit) { return llmLogDao.queryRecent(limit); }
    @Override public Object queryLlmLogById(Long id) { return llmLogDao.queryById(id); }

    @Override public Object queryCases(String keyword, int limit) {
        if (keyword != null && !keyword.isBlank()) {
            return caseDao.queryByKeyword(keyword, limit);
        }
        return caseDao.queryTop(limit);
    }

    @Override public Object queryFeedback(int limit, String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return feedbackDao.queryByAgentId(agentId, limit);
        }
        return feedbackDao.queryRecent(limit);
    }
}
