package cn.bugstack.ai.trigger.service.conversation;

import cn.bugstack.ai.infrastructure.dao.IAgentExecutionDao;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationSessionService {

    @Resource private IAgentExecutionDao executionDao;
    @Resource private IAiAgentDao agentDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private IAiSessionDao sessionDao;
    @Resource private IChatMessageDao messageDao;
    @Resource private IMemorySummaryDao summaryDao;
    @Resource private IMemoryStateDao stateDao;
    @Resource private IMemoryToolResultDao toolResultDao;

    public AiSession create(String agentId, String title, String modelId) {
        requireAgent(agentId);
        AiSession session = AiSession.builder()
                .sessionId(ConversationIdPolicy.create())
                .agentId(agentId)
                .title(safeTitle(title))
                .modelId(safe(modelId))
                .preview("")
                .messageCount(0)
                .status(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .lastMessageAt(LocalDateTime.now())
                .build();
        sessionDao.insert(session);
        return session;
    }

    public AiSession requireOwned(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        AiSession session = sessionDao.queryByAgentAndSession(agentId, sessionId);
        if (session == null) throw new IllegalArgumentException("Conversation does not belong to this Agent");
        return session;
    }

    public List<AiSession> list(String agentId, int limit) {
        requireAgent(agentId);
        return sessionDao.queryByAgentId(agentId, Math.max(1, Math.min(limit, 100))).stream()
                .filter(session -> session.getStatus() == null || session.getStatus() == 1)
                .toList();
    }

    public Map<String, Object> detail(String agentId, String sessionId) {
        AiSession session = requireOwned(agentId, sessionId);
        List<ChatMessage> messages = messageDao.queryBySessionId(sessionId).stream()
                .filter(message -> agentId.equals(message.getAgentId()))
                .toList();
        List<AiFeedback> feedback = feedbackDao.queryBySession(agentId, sessionId, 20);
        List<AiCase> cases = caseDao.queryBySession(agentId, sessionId, 10);
        MemorySummary summary = summaryDao.queryLatest(sessionId);
        AgentExecution latestExecution = executionDao.queryLatestBySession(agentId, sessionId);

        LinkedHashMap<String, Object> memory = new LinkedHashMap<>();
        memory.put("summary", nullable(summary));
        memory.put("state", nullable(stateDao.queryLatest(sessionId)));
        memory.put("toolResults", toolResultDao.queryBySession(sessionId, 50));

        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("session", session);
        detail.put("messages", messages);
        detail.put("memory", memory);
        detail.put("feedback", feedback);
        detail.put("cases", cases);
        detail.put("overview", overview(messages, feedback, cases, summary, latestExecution));
        return detail;
    }

    public AiSession rename(String agentId, String sessionId, String title) {
        AiSession session = requireOwned(agentId, sessionId);
        String sanitizedTitle = safeTitle(title);
        sessionDao.updateTitle(sessionId, sanitizedTitle);
        session.setTitle(sanitizedTitle);
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    public AiSession delete(String agentId, String sessionId) {
        AiSession session = requireOwned(agentId, sessionId);
        sessionDao.softDelete(sessionId);
        session.setStatus(0);
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private void requireAgent(String agentId) {
        if (agentId == null || agentId.isBlank() || agentDao.queryByAgentId(agentId) == null) {
            throw new IllegalArgumentException("Agent does not exist");
        }
    }

    private static String safeTitle(String value) {
        String title = safe(value);
        return title.isBlank() ? "新对话" : title.substring(0, Math.min(title.length(), 100));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static Object nullable(Object value) {
        return value == null ? Map.of() : value;
    }

    private static Map<String, Object> overview(List<ChatMessage> messages,
                                                List<AiFeedback> feedback,
                                                List<AiCase> cases,
                                                MemorySummary summary,
                                                AgentExecution latestExecution) {
        long userMessageCount = messages.stream().filter(item -> "user".equals(item.getRole())).count();
        long assistantMessageCount = messages.stream().filter(item -> "assistant".equals(item.getRole())).count();
        long toolMessageCount = messages.stream().filter(item -> "tool".equals(item.getRole())).count();
        long openFeedbackCount = feedback.stream()
                .filter(item -> !"RESOLVED".equals(item.getStatus()) && !"INVALID".equals(item.getStatus()))
                .count();
        long promotedFeedbackCount = feedback.stream()
                .filter(item -> "PROMOTED".equals(item.getStatus()))
                .count();

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("messageCount", messages.size());
        result.put("userMessageCount", userMessageCount);
        result.put("assistantMessageCount", assistantMessageCount);
        result.put("toolMessageCount", toolMessageCount);
        result.put("feedbackCount", feedback.size());
        result.put("openFeedbackCount", openFeedbackCount);
        result.put("promotedFeedbackCount", promotedFeedbackCount);
        result.put("caseCount", cases.size());
        result.put("hasMemorySummary", summary != null && safe(summary.getSummary()).length() >= 20);
        result.put("latestRouteType", latestExecution == null ? "" : safe(latestExecution.getRouteType()));
        result.put("latestExecutionStatus", latestExecution == null ? "" : safe(latestExecution.getStatus()));
        result.put("latestModelId", latestExecution == null ? "" : safe(latestExecution.getModelId()));
        result.put("latestExecutionId", latestExecution == null ? "" : safe(latestExecution.getExecutionId()));
        result.put("latestExecutionAt", latestExecution == null ? null : latestExecution.getUpdatedAt());
        return result;
    }
}
