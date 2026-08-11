package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.memory.RollingSummaryPolicy;
import cn.bugstack.ai.domain.agent.service.memory.TokenBudgetEstimator;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import cn.bugstack.ai.domain.agent.service.memory.ContextBudgetPolicy;
import cn.bugstack.ai.domain.agent.service.memory.MemorySummaryLock;
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
    @Resource private ApplicationContext applicationContext;
    @Resource private LongTermMemoryPort longTermMemoryPort;
    @Resource private MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;
    @Resource private ShortTermMemoryPersistenceService persistenceService;
    @Resource private ContextBudgetPolicy contextBudgetPolicy;
    @Resource private MemorySummaryLock summaryLock;
    @Value("${agent.memory.retain-messages:24}") private int retainMessages = 24;
    @Value("${agent.memory.min-new-user-turns:4}") private int minNewMeaningfulUserTurns = 4;
    @Value("${agent.memory.summary-lock-ttl-seconds:180}") private long summaryLockTtlSeconds = 180L;

    public SummaryRefreshResult refreshIfNeeded(String agentId, String sessionId, String modelId) {
        String lockKey = "agent:memory:summary:" + agentId + ":" + sessionId;
        MemorySummaryLock.Lease lease = summaryLock.tryAcquire(lockKey,
                java.time.Duration.ofSeconds(summaryLockTtlSeconds));
        if (lease == null) return new SummaryRefreshResult(SummaryRefreshResult.Status.LOCK_BUSY);
        try {
            List<ChatMessage> messages = messageDao.queryBySessionId(sessionId);
            MemorySummary previous = summaryDao.queryLatest(sessionId);
            long covered = previous == null ? 0 : previous.getEndMessageId();
            List<RollingSummaryPolicy.MemoryMessage> memoryMessages = messages.stream()
                    .map(message -> new RollingSummaryPolicy.MemoryMessage(message.getId(), message.getRole(), message.getContent()))
                    .toList();
            List<java.util.Map<String, Object>> contextMessages = messages.stream().map(message -> {
                java.util.Map<String, Object> mapped = new java.util.LinkedHashMap<>();
                mapped.put("role", message.getRole());
                mapped.put("content", summaryContent(message));
                mapped.put("tool_arguments", message.getToolArguments());
                return mapped;
            }).toList();
            ContextBudgetPolicy.BudgetDecision budget = contextBudgetPolicy.decide(
                    modelId, PROMPT, "", contextMessages);
            RollingSummaryPolicy policy = new RollingSummaryPolicy(new TokenBudgetEstimator(),
                    budget.softSummaryThreshold(), budget.hardFoldThreshold(), retainMessages,
                    minNewMeaningfulUserTurns);
            RollingSummaryPolicy.SummaryPlan plan = policy.plan(memoryMessages, covered);
            if (!plan.required()) return new SummaryRefreshResult(SummaryRefreshResult.Status.NOT_REQUIRED);

            long latestMessageId = messages.stream().map(ChatMessage::getId).filter(id -> id != null)
                    .mapToLong(Long::longValue).max().orElse(0L);
            StringBuilder input = new StringBuilder();
            if (previous != null) input.append("PREVIOUS SUMMARY:\n").append(previous.getSummary()).append("\nNEW MESSAGES:\n");
            messages.stream().filter(message -> message.getId() != null
                    && message.getId() >= plan.startMessageId() && message.getId() <= plan.endMessageId())
                    .forEach(message -> input.append('[').append(message.getId()).append(' ').append(message.getRole()).append("] ")
                            .append(summaryContent(message)).append('\n'));
            OpenAiChatModel model = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId), OpenAiChatModel.class);
            String raw = ChatClient.builder(model).defaultSystem(PROMPT).build().prompt().user(input.toString()).call().content();
            if (raw == null || raw.contains("```")) throw new IllegalArgumentException("Memory summary must be plain JSON");
            JSONObject result = JSON.parseObject(raw);
            String summary = result.getString("summary");
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("Memory summary is empty");
            boolean saved = persistenceService.saveIfUnchanged(agentId, sessionId, modelId, previous,
                    new ShortTermMemoryPersistenceService.RollingSummarySnapshot(plan, latestMessageId,
                            new TokenBudgetEstimator().estimate(summary)), result, summary);
            if (saved && memoryQueryAdmissionPolicy.shouldStoreSummary(summary)) {
                longTermMemoryPort.store(new LongTermMemoryPort.MemoryFact(agentId, agentId, "SESSION_SUMMARY", summary,
                        sessionId, "system-derived-after-short-term-threshold"));
            }
            return new SummaryRefreshResult(saved
                    ? SummaryRefreshResult.Status.SAVED : SummaryRefreshResult.Status.NOT_REQUIRED);
        } finally {
            summaryLock.release(lease);
        }
    }

    private String summaryContent(ChatMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        if ("tool".equalsIgnoreCase(message.getRole())) content = MemoryFoldingPipeline.foldPlainText(content);
        if (content.length() > 4_000) content = content.substring(0, 4_000) + "...[summary-input-truncated]";
        return content;
    }

}
