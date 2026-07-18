package cn.bugstack.ai.trigger.service.conversation;

import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ConversationSessionService {
    @Resource private IAiAgentDao agentDao;
    @Resource private IAiSessionDao sessionDao;
    @Resource private IChatMessageDao messageDao;
    @Resource private IMemorySummaryDao summaryDao;
    @Resource private IMemoryStateDao stateDao;
    @Resource private IMemoryToolResultDao toolResultDao;

    public AiSession create(String agentId, String title, String modelId) {
        requireAgent(agentId);
        AiSession session = AiSession.builder().sessionId(ConversationIdPolicy.create()).agentId(agentId)
                .title(safeTitle(title)).modelId(safe(modelId)).preview("").messageCount(0).status(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).lastMessageAt(LocalDateTime.now()).build();
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
        return Map.of("session", session, "messages", messageDao.queryBySessionId(sessionId).stream()
                        .filter(message -> agentId.equals(message.getAgentId())).toList(),
                "memory", Map.of("summary", nullable(summaryDao.queryLatest(sessionId)),
                        "state", nullable(stateDao.queryLatest(sessionId)),
                        "toolResults", toolResultDao.queryBySession(sessionId, 50)));
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

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static Object nullable(Object value) { return value == null ? Map.of() : value; }
}
