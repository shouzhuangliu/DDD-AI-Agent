package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryChangeLogDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryIndexOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryChangeLog;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryIndexOutbox;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 业务长期记忆的唯一写入口：自动新增、更新和软删除。 */
@Service
public class AgentMemoryLifecycleService {
    private final IAgentMemoryCardDao cardDao;
    private final IAgentMemoryChangeLogDao changeLogDao;
    private final IAgentMemoryIndexOutboxDao outboxDao;

    public AgentMemoryLifecycleService(IAgentMemoryCardDao cardDao, IAgentMemoryChangeLogDao changeLogDao,
                                       IAgentMemoryIndexOutboxDao outboxDao) {
        this.cardDao = cardDao; this.changeLogDao = changeLogDao; this.outboxDao = outboxDao;
    }

    @Transactional
    public Result upsert(UpsertCommand command) {
        validate(command.agentId(), command.memoryType(), command.memoryKey(), command.title(), command.description(),
                command.content(), command.sourceType(), command.sourceId(), command.evidenceQuote(), command.reason());
        AgentMemoryCard previous = cardDao.queryActiveByIdentity(command.agentId(), upper(command.memoryType()), command.memoryKey().trim());
        String memoryId = previous == null ? UUID.randomUUID().toString() : previous.getMemoryId();
        int version = previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1;
        String operation = previous == null ? "CREATE" : "UPDATE";
        LocalDateTime now = LocalDateTime.now();
        if (previous != null) cardDao.supersedeByKey(command.agentId(), command.memoryKey().trim());
        AgentMemoryCard card = AgentMemoryCard.builder().memoryId(memoryId).agentId(command.agentId().trim())
                .memoryType(upper(command.memoryType())).memoryKey(command.memoryKey().trim()).version(version)
                .title(command.title().trim()).description(command.description().trim()).contentJson(command.content().trim())
                .status("PUBLISHED").isDeleted(0).importance(bound(command.importance())).pinned(command.pinned() ? 1 : 0)
                .updatedReason(command.reason().trim()).sourceCandidateId("").sourceCaseId("CASE".equalsIgnoreCase(command.sourceType()) ? command.sourceId().trim() : "")
                .effectiveAt(now).publishedBy("agent-memory-runtime").publishedAt(now).createdAt(now).updatedAt(now).build();
        cardDao.insert(card);
        audit(card, operation, command.reason(), command.sourceType(), command.sourceId(), now);
        if (previous != null) outboxDao.insert(event(previous, "DELETE", command.reason(), now));
        outboxDao.insert(event(card, "UPSERT", JSON.toJSONString(card), now));
        return new Result(memoryId, version, operation);
    }

    @Transactional
    public void retire(RetireCommand command) {
        validate(command.agentId(), command.memoryId(), command.sourceType(), command.sourceId(), command.evidenceQuote(), command.reason());
        AgentMemoryCard card = cardDao.queryActiveByMemoryId(command.agentId(), command.memoryId());
        if (card == null) throw new IllegalArgumentException("当前 Agent 不存在有效长期记忆");
        if (cardDao.softDelete(command.agentId(), command.memoryId(), command.reason().trim()) != 1) throw new IllegalStateException("长期记忆状态已变化");
        LocalDateTime now = LocalDateTime.now();
        audit(card, "RETIRE", command.reason(), command.sourceType(), command.sourceId(), now);
        outboxDao.insert(event(card, "DELETE", command.reason(), now));
    }

    private void audit(AgentMemoryCard card, String operation, String reason, String sourceType, String sourceId, LocalDateTime now) {
        changeLogDao.insert(AgentMemoryChangeLog.builder().changeId(UUID.randomUUID().toString()).agentId(card.getAgentId())
                .memoryId(card.getMemoryId()).memoryVersion(card.getVersion()).operation(operation).reason(reason.trim())
                .sourceType(upper(sourceType)).sourceId(sourceId.trim()).createdAt(now).build());
    }
    private AgentMemoryIndexOutbox event(AgentMemoryCard card, String type, String payload, LocalDateTime now) {
        return AgentMemoryIndexOutbox.builder().eventId(UUID.randomUUID().toString()).agentId(card.getAgentId())
                .memoryId(card.getMemoryId()).memoryVersion(card.getVersion()).eventType(type).payloadJson(payload)
                .status("PENDING").attempts(0).nextRetryAt(now).lastError("").createdAt(now).updatedAt(now).build();
    }
    private static void validate(String... values) { for (String value : values) if (value == null || value.isBlank()) throw new IllegalArgumentException("长期记忆操作字段不能为空"); }
    private static int bound(int value) { return Math.max(0, Math.min(100, value)); }
    private static String upper(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    public record UpsertCommand(String agentId, String memoryType, String memoryKey, String title, String description,
                                String content, int importance, boolean pinned, String sourceType, String sourceId,
                                String evidenceQuote, String reason) { }
    public record RetireCommand(String agentId, String memoryId, String sourceType, String sourceId, String evidenceQuote, String reason) { }
    public record Result(String memoryId, int version, String operation) { }
}
