package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryExtractionCursorDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryExtractionCursor;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.trigger.service.analysis.AgentEvaluationContextBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationMemoryCandidateExtractor {

    private final IChatMessageDao messageDao;
    private final IAgentMemoryExtractionCursorDao cursorDao;
    private final AgentMemoryCandidateService candidateService;
    private final MemoryCandidateModelClient modelClient;
    private final MemoryQueryAdmissionPolicy admissionPolicy;
    private final AgentEvaluationContextBuilder contextBuilder;

    public ConversationMemoryCandidateExtractor(IChatMessageDao messageDao,
                                                IAgentMemoryExtractionCursorDao cursorDao,
                                                AgentMemoryCandidateService candidateService,
                                                MemoryCandidateModelClient modelClient,
                                                MemoryQueryAdmissionPolicy admissionPolicy,
                                                AgentEvaluationContextBuilder contextBuilder) {
        this.messageDao = messageDao;
        this.cursorDao = cursorDao;
        this.candidateService = candidateService;
        this.modelClient = modelClient;
        this.admissionPolicy = admissionPolicy;
        this.contextBuilder = contextBuilder;
    }

    public ExtractionResult extractIfEligible(String agentId, String sessionId, String modelId) {
        AgentMemoryExtractionCursor cursor = cursorDao.query(agentId, sessionId);
        long covered = cursor == null || cursor.getLastMessageId() == null ? 0L : cursor.getLastMessageId();
        if (cursor == null) cursorDao.insertIgnore(AgentMemoryExtractionCursor.builder()
                .agentId(agentId).sessionId(sessionId).lastMessageId(0L).version(0)
                .lastStatus("IDLE").retryCount(0).lastError("").build());
        List<ChatMessage> fresh = messageDao.queryBySessionId(sessionId).stream()
                .filter(item -> item.getId() != null && item.getId() > covered)
                .filter(item -> agentId.equals(item.getAgentId()))
                .toList();
        if (fresh.isEmpty()) return new ExtractionResult(Status.NO_NEW_MESSAGES, "");
        String latestUser = fresh.stream().filter(item -> "user".equalsIgnoreCase(item.getRole()))
                .reduce((left, right) -> right).map(ChatMessage::getContent).orElse("");
        if (!admissionPolicy.shouldRecall(latestUser)) {
            return new ExtractionResult(Status.SKIPPED_LOW_INFORMATION, "");
        }
        long latestId = fresh.stream().mapToLong(ChatMessage::getId).max().orElse(covered);
        StringBuilder context = new StringBuilder(safe(contextBuilder.build(agentId, fresh)))
                .append("\n[仅允许从以下新增会话原文抽取候选]\n");
        fresh.forEach(item -> context.append('[').append(item.getId()).append(' ')
                .append(item.getRole()).append("] ").append(safe(item.getContent())).append('\n'));
        try {
            MemoryCandidateModelClient.Extraction extraction = modelClient.extract(
                    new MemoryCandidateModelClient.ExtractionRequest(agentId, sessionId, modelId, context.toString()));
            if (extraction == null || !extraction.eligible()) {
                advance(agentId, sessionId, covered, latestId);
                return new ExtractionResult(Status.NO_CANDIDATE, "");
            }
            if ("RESOLVED_CASE".equalsIgnoreCase(extraction.memoryType())) {
                throw new IllegalArgumentException("会话抽取不能生成 RESOLVED_CASE");
            }
            List<AgentMemoryCandidateService.EvidenceInput> evidence = extraction.evidence().stream()
                    .map(item -> new AgentMemoryCandidateService.EvidenceInput("MESSAGE", String.valueOf(item.messageId()),
                            sessionId, item.messageId(), "", item.quote())).toList();
            String candidateId = candidateService.submitCandidate(new AgentMemoryCandidateService.SubmitCandidate(
                    agentId, extraction.memoryType(), extraction.memoryKey(), extraction.title(), extraction.summary(),
                    extraction.contentJson(), "SESSION", sessionId + ":" + latestId, sessionId, "",
                    extraction.confidence(), modelId, "memory-candidate-v1", evidence));
            advance(agentId, sessionId, covered, latestId);
            return new ExtractionResult(Status.CANDIDATE_CREATED, candidateId);
        } catch (RuntimeException exception) {
            cursorDao.markFailure(agentId, sessionId, boundedError(exception));
            throw exception;
        }
    }

    private void advance(String agentId, String sessionId, long covered, long latestId) {
        if (cursorDao.advance(agentId, sessionId, covered, latestId) != 1) {
            throw new IllegalStateException("长期记忆抽取游标发生并发变化");
        }
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String boundedError(Exception error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.substring(0, Math.min(2000, value.length()));
    }
    public enum Status { NO_NEW_MESSAGES, SKIPPED_LOW_INFORMATION, NO_CANDIDATE, CANDIDATE_CREATED }
    public record ExtractionResult(Status status, String candidateId) { }
}
