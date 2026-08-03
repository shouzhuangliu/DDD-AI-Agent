package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.memory.RollingSummaryPolicy;
import cn.bugstack.ai.domain.agent.service.memory.TokenBudgetEstimator;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShortTermMemoryService {

    private static final String PROMPT = """
            Produce one plain JSON object only. Preserve user goals, explicit constraints, named entities,
            pending work, completed work, decisions and important tool conclusions. Do not invent facts.
            Shape: {"summary":"concise rolling summary","goals":[],"constraints":[],"entities":[],"pending":[],"completed":[]}.
            """;

    @Resource private IChatMessageDao messageDao;
    @Resource private IMemorySummaryDao summaryDao;
    @Resource private IMemoryStateDao stateDao;
    @Resource private ApplicationContext applicationContext;
    @Resource private LongTermMemoryPort longTermMemoryPort;
    @Resource private MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;
    @Value("${agent.memory.summary-token-threshold:8000}") private int tokenThreshold;
    @Value("${agent.memory.summary-hard-limit:16000}") private int hardTokenLimit;
    @Value("${agent.memory.retain-messages:24}") private int retainMessages;
    @Value("${agent.memory.min-new-user-turns:4}") private int minNewMeaningfulUserTurns;

    public void refreshIfNeeded(String agentId, String sessionId, String modelId) {
        List<ChatMessage> messages = messageDao.queryBySessionId(sessionId);
        MemorySummary previous = summaryDao.queryLatest(sessionId);
        long covered = previous == null ? 0 : previous.getEndMessageId();
        RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(), tokenThreshold,
                hardTokenLimit, retainMessages, minNewMeaningfulUserTurns);
        RollingSummaryPolicy.SummaryPlan plan = policy.plan(messages.stream()
                .map(message -> new RollingSummaryPolicy.MemoryMessage(message.getId(), message.getRole(), message.getContent()))
                .toList(), covered);
        if (!plan.required()) return;

        StringBuilder input = new StringBuilder();
        if (previous != null) input.append("PREVIOUS SUMMARY:\n").append(previous.getSummary()).append("\nNEW MESSAGES:\n");
        messages.stream().filter(message -> message.getId() >= plan.startMessageId() && message.getId() <= plan.endMessageId())
                .forEach(message -> input.append('[').append(message.getId()).append(' ').append(message.getRole()).append("] ")
                        .append(message.getContent() == null ? "" : message.getContent()).append('\n'));
        OpenAiChatModel model = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId), OpenAiChatModel.class);
        String raw = ChatClient.builder(model).defaultSystem(PROMPT).build().prompt().user(input.toString()).call().content();
        if (raw == null || raw.contains("```")) throw new IllegalArgumentException("Memory summary must be plain JSON");
        JSONObject result = JSON.parseObject(raw);
        String summary = result.getString("summary");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("Memory summary is empty");
        int version = previous == null ? 1 : previous.getVersion() + 1;
        LocalDateTime now = LocalDateTime.now();
        summaryDao.supersede(sessionId);
        summaryDao.insert(MemorySummary.builder().agentId(agentId).sessionId(sessionId).version(version)
                .startMessageId(previous == null ? plan.startMessageId() : previous.getStartMessageId())
                .endMessageId(plan.endMessageId()).summary(summary).modelId(modelId)
                .tokenCount(new TokenBudgetEstimator().estimate(summary)).status("ACTIVE").createdAt(now).build());
        stateDao.insert(MemoryState.builder().agentId(agentId).sessionId(sessionId).version(version)
                .goalsJson(arrayJson(result, "goals")).constraintsJson(arrayJson(result, "constraints"))
                .entitiesJson(arrayJson(result, "entities")).pendingJson(arrayJson(result, "pending"))
                .completedJson(arrayJson(result, "completed")).createdAt(now).build());
        if (memoryQueryAdmissionPolicy.shouldStoreSummary(summary)) {
            longTermMemoryPort.store(new LongTermMemoryPort.MemoryFact(agentId, agentId, "SESSION_SUMMARY", summary,
                    sessionId, "system-derived-after-short-term-threshold"));
        }
    }

    private String arrayJson(JSONObject result, String key) {
        return result.getJSONArray(key) == null ? "[]" : result.getJSONArray(key).toJSONString();
    }
}
