package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemoryState;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 只负责在短事务中提交已经在事务外生成的摘要结果。 */
@Service
public class ShortTermMemoryPersistenceService {

    @Resource private IChatMessageDao messageDao;
    @Resource private IMemorySummaryDao summaryDao;
    @Resource private IMemoryStateDao stateDao;

    @Transactional
    public boolean saveIfUnchanged(String agentId, String sessionId, String modelId,
                                   MemorySummary previous, RollingSummarySnapshot snapshot,
                                   JSONObject result, String summary) {
        List<ChatMessage> latestMessages = messageDao.queryBySessionId(sessionId);
        long latestId = latestMessages.stream().map(ChatMessage::getId)
                .filter(id -> id != null).mapToLong(Long::longValue).max().orElse(0L);
        if (latestId != snapshot.latestMessageId()) return false;

        MemorySummary current = summaryDao.queryLatest(sessionId);
        if (!sameSummary(previous, current)) return false;

        int version = previous == null ? 1 : previous.getVersion() + 1;
        LocalDateTime now = LocalDateTime.now();
        summaryDao.supersede(sessionId);
        summaryDao.insert(MemorySummary.builder().agentId(agentId).sessionId(sessionId).version(version)
                .startMessageId(previous == null ? snapshot.plan().startMessageId() : previous.getStartMessageId())
                .endMessageId(snapshot.plan().endMessageId()).summary(summary).modelId(modelId)
                .tokenCount(snapshot.summaryTokenCount()).status("ACTIVE").createdAt(now).build());
        stateDao.insert(MemoryState.builder().agentId(agentId).sessionId(sessionId).version(version)
                .goalsJson(arrayJson(result, "goals")).constraintsJson(arrayJson(result, "constraints"))
                .entitiesJson(arrayJson(result, "entities")).pendingJson(arrayJson(result, "pending"))
                .completedJson(arrayJson(result, "completed")).createdAt(now).build());
        return true;
    }

    private boolean sameSummary(MemorySummary expected, MemorySummary actual) {
        if (expected == null || actual == null) return expected == actual;
        return expected.getId() != null && expected.getId().equals(actual.getId())
                && Objects.equals(expected.getVersion(), actual.getVersion())
                && Objects.equals(expected.getEndMessageId(), actual.getEndMessageId());
    }

    private String arrayJson(JSONObject result, String key) {
        return result.getJSONArray(key) == null ? "[]" : result.getJSONArray(key).toJSONString();
    }

    public record RollingSummarySnapshot(
            cn.bugstack.ai.domain.agent.service.memory.RollingSummaryPolicy.SummaryPlan plan,
            long latestMessageId,
            int summaryTokenCount) {
    }
}
