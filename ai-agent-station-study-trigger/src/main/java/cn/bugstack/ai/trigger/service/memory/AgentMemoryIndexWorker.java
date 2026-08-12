package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryIndexOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryIndexOutbox;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.memory.long-term", name = "provider", havingValue = "pgvector")
public class AgentMemoryIndexWorker {

    private final IAgentMemoryIndexOutboxDao outboxDao;
    private final LongTermMemoryPort indexPort;
    private final int maxAttempts;

    public AgentMemoryIndexWorker(IAgentMemoryIndexOutboxDao outboxDao,
                                  LongTermMemoryPort indexPort,
                                  @Value("${agent.memory.long-term.index.max-attempts:5}") int maxAttempts) {
        this.outboxDao = outboxDao;
        this.indexPort = indexPort;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${agent.memory.long-term.index.poll-delay-ms:3000}")
    public void processNext() {
        AgentMemoryIndexOutbox event = outboxDao.queryClaimable();
        if (event == null || event.getId() == null || outboxDao.claim(event.getId()) != 1) return;
        try {
            if ("DELETE".equalsIgnoreCase(event.getEventType())) {
                indexPort.delete(event.getAgentId(), event.getMemoryId(), safeVersion(event));
            } else {
                indexPort.index(toDocument(event));
            }
            outboxDao.markDone(event.getEventId());
        } catch (Exception exception) {
            String error = abbreviate(exception.getMessage(), 1000);
            int attemptsAfterFailure = safeAttempts(event) + 1;
            if (attemptsAfterFailure >= maxAttempts) {
                outboxDao.markFailed(event.getEventId(), error);
            } else {
                long delaySeconds = Math.min(900, 5L * (1L << Math.min(safeAttempts(event), 8)));
                outboxDao.markRetry(event.getEventId(), error, LocalDateTime.now().plusSeconds(delaySeconds));
            }
            log.warn("长期记忆索引事件执行失败，eventId={}，attempt={}/{}，error={}",
                    event.getEventId(), attemptsAfterFailure, maxAttempts, error);
        }
    }

    private LongTermMemoryPort.PublishedMemoryDocument toDocument(AgentMemoryIndexOutbox event) {
        JSONObject payload = JSON.parseObject(event.getPayloadJson());
        if (payload == null) throw new IllegalArgumentException("索引事件缺少记忆卡片内容");
        String title = safe(payload.getString("title"));
        String description = safe(payload.getString("description"));
        String content = searchableContent(payload.getString("contentJson"));
        String searchText = String.join("\n", title, description, content).trim();
        if (searchText.isBlank()) throw new IllegalArgumentException("索引事件缺少可检索文本");
        return new LongTermMemoryPort.PublishedMemoryDocument(
                event.getAgentId(), event.getMemoryId(), safeVersion(event),
                safe(payload.getString("memoryType")), title, description, searchText,
                safe(payload.getString("sourceCaseId")), "PUBLISHED");
    }

    private static String searchableContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) return "";
        return abbreviate(contentJson, 2000);
    }

    private static int safeVersion(AgentMemoryIndexOutbox event) {
        return event.getMemoryVersion() == null ? 1 : event.getMemoryVersion();
    }

    private static int safeAttempts(AgentMemoryIndexOutbox event) {
        return event.getAttempts() == null ? 0 : event.getAttempts();
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String abbreviate(String value, int maxLength) {
        String safe = value == null || value.isBlank() ? "unknown error" : value.trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
