package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.MemoryPublicationPolicy;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import cn.bugstack.ai.trigger.service.analysis.AgentMemoryProfileService;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AgentMemoryCandidateService {

    private final IAgentMemoryCandidateDao candidateDao;
    private final IAgentMemoryEvidenceDao evidenceDao;
    private final IAgentMemoryCardDao cardDao;
    private final IAgentMemoryIndexOutboxDao outboxDao;
    private final IAiCaseDao caseDao;
    private final IChatMessageDao messageDao;
    private final MemoryPublicationPolicy policy = new MemoryPublicationPolicy();
    @Autowired(required = false)
    private AgentMemoryProfileService profileService;

    public AgentMemoryCandidateService(IAgentMemoryCandidateDao candidateDao,
                                       IAgentMemoryEvidenceDao evidenceDao,
                                       IAgentMemoryCardDao cardDao,
                                       IAgentMemoryIndexOutboxDao outboxDao,
                                       IAiCaseDao caseDao,
                                       IChatMessageDao messageDao) {
        this.candidateDao = candidateDao;
        this.evidenceDao = evidenceDao;
        this.cardDao = cardDao;
        this.outboxDao = outboxDao;
        this.caseDao = caseDao;
        this.messageDao = messageDao;
    }

    @Transactional
    public String submitCandidate(SubmitCandidate request) {
        requireText(request.agentId(), "agentId");
        requireText(request.memoryKey(), "memoryKey");
        requireText(request.title(), "title");
        requireText(request.summary(), "summary");
        requireText(request.sourceType(), "sourceType");
        requireText(request.sourceId(), "sourceId");
        policy.requireCandidateType(request.memoryType(), request.sourceType());
        if (request.evidence() == null || request.evidence().isEmpty()) {
            throw new IllegalArgumentException("长期记忆候选必须包含可追溯证据");
        }
        request.evidence().forEach(item -> validateEvidence(request.agentId(), item));

        String candidateId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AgentMemoryCandidate candidate = AgentMemoryCandidate.builder()
                .candidateId(candidateId).agentId(request.agentId()).memoryType(normalize(request.memoryType()))
                .memoryKey(request.memoryKey().trim()).title(request.title().trim()).summary(request.summary().trim())
                .contentJson(nonBlank(request.contentJson(), "{}"))
                .sourceType(normalize(request.sourceType())).sourceId(request.sourceId().trim())
                .sourceSessionId(nonBlank(request.sourceSessionId(), ""))
                .sourceCaseId(nonBlank(request.sourceCaseId(), ""))
                .confidence(Math.max(0, Math.min(100, request.confidence())))
                .status("EXTRACTED").extractionModelId(nonBlank(request.modelId(), ""))
                .promptVersion(nonBlank(request.promptVersion(), ""))
                .createdAt(now).updatedAt(now).build();
        if (candidateDao.insertIgnore(candidate) != 1) {
            AgentMemoryCandidate existing = candidateDao.queryByUniqueSource(
                    request.agentId(), normalize(request.memoryType()), request.memoryKey().trim(),
                    normalize(request.sourceType()), request.sourceId().trim());
            if (existing == null || existing.getCandidateId() == null || existing.getCandidateId().isBlank()) {
                throw new IllegalStateException("长期记忆候选幂等写入失败");
            }
            return existing.getCandidateId();
        }
        for (EvidenceInput item : request.evidence()) {
            evidenceDao.insertIgnore(toEvidence(candidateId, request.agentId(), item, now));
        }
        policy.requireTransition(MemoryPublicationPolicy.CandidateStatus.EXTRACTED,
                MemoryPublicationPolicy.CandidateStatus.PENDING_REVIEW);
        if (candidateDao.transition(request.agentId(), candidateId, "EXTRACTED", "PENDING_REVIEW",
                "", "等待人工审核", null) != 1) {
            throw new IllegalStateException("长期记忆候选状态推进失败");
        }
        return candidateId;
    }

    public String submitResolvedCaseCandidate(AiCase item, String reason) {
        if (item == null || !"RESOLVED".equals(normalize(item.getStatus()))) {
            throw new IllegalStateException("只有已解决 Case 才能生成长期记忆候选");
        }
        String content = JSON.toJSONString(java.util.Map.of(
                "problem", nonBlank(item.getSummary(), item.getTitle()),
                "resolution", nonBlank(item.getResolution(), ""),
                "reason", nonBlank(reason, ""),
                "severity", nonBlank(item.getSeverity(), "")));
        String quote = nonBlank(item.getSummary(), item.getTitle());
        return submitCandidate(new SubmitCandidate(item.getAgentId(), "RESOLVED_CASE",
                item.getAgentId() + ":resolved-case:" + item.getCaseId(), item.getTitle(),
                quote, content, "CASE", item.getCaseId(), "", item.getCaseId(),
                item.getConfidence() == null ? 100 : item.getConfidence().intValue(),
                nonBlank(item.getSourceModel(), "server"), "resolved-case-v1",
                List.of(new EvidenceInput("CASE", item.getCaseId(), "", null, "", quote))));
    }

    public void approve(String agentId, String candidateId, String reviewer, String comment) {
        transitionReview(agentId, candidateId, reviewer, comment, "APPROVED");
    }

    public void reject(String agentId, String candidateId, String reviewer, String comment) {
        transitionReview(agentId, candidateId, reviewer, comment, "REJECTED");
    }

    public List<AgentMemoryCandidate> list(String agentId, String status, int limit) {
        requireText(agentId, "agentId");
        String normalizedStatus = normalize(nonBlank(status, "PENDING_REVIEW"));
        return candidateDao.queryByStatus(agentId, normalizedStatus, Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public void retire(String agentId, String memoryId, String actor, String reason) {
        requireText(actor, "actor");
        requireText(reason, "reason");
        AgentMemoryCard card = cardDao.queryPublishedByMemoryId(agentId, memoryId);
        if (card == null) throw new IllegalArgumentException("当前 Agent 不存在可退役的已发布记忆");
        if (cardDao.retireByMemoryId(agentId, memoryId) != 1) {
            throw new IllegalStateException("记忆状态已变化，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        outboxDao.insert(AgentMemoryIndexOutbox.builder().eventId(UUID.randomUUID().toString())
                .agentId(agentId).memoryId(memoryId).memoryVersion(card.getVersion())
                .eventType("DELETE").payloadJson(JSON.toJSONString(java.util.Map.of(
                        "actor", actor.trim(), "reason", reason.trim())))
                .status("PENDING").attempts(0).nextRetryAt(now).lastError("")
                .createdAt(now).updatedAt(now).build());
        rebuildProfile(agentId);
    }

    @Transactional
    public void retireResolvedCaseMemories(String agentId, String caseId, String reason) {
        requireText(agentId, "agentId");
        requireText(caseId, "caseId");
        List<AgentMemoryCard> published = cardDao.queryPublishedByCaseId(agentId, caseId);
        if (published == null || published.isEmpty()) return;
        if (cardDao.retireByCaseId(agentId, caseId) < 1) return;
        LocalDateTime now = LocalDateTime.now();
        for (AgentMemoryCard card : published) {
            outboxDao.insert(deleteEvent(card, nonBlank(reason, "Case 已重新打开"), now));
        }
        rebuildProfile(agentId);
    }

    private void transitionReview(String agentId, String candidateId, String reviewer, String comment, String target) {
        requireText(reviewer, "reviewer");
        requireText(comment, "comment");
        policy.requireTransition(MemoryPublicationPolicy.CandidateStatus.PENDING_REVIEW,
                MemoryPublicationPolicy.CandidateStatus.valueOf(target));
        if (candidateDao.transition(agentId, candidateId, "PENDING_REVIEW", target,
                reviewer.trim(), comment.trim(), LocalDateTime.now()) != 1) {
            throw new IllegalStateException("候选状态已变化或不存在");
        }
    }

    @Transactional
    public PublishedMemory publish(String agentId, String candidateId, String publisher) {
        requireText(publisher, "publisher");
        AgentMemoryCandidate candidate = candidateDao.queryByCandidateId(agentId, candidateId);
        if (candidate == null) throw new IllegalArgumentException("长期记忆候选不存在");
        if (!agentId.equals(candidate.getAgentId())) throw new IllegalArgumentException("候选不属于当前 Agent");
        policy.requireTransition(MemoryPublicationPolicy.CandidateStatus.valueOf(normalize(candidate.getStatus())),
                MemoryPublicationPolicy.CandidateStatus.PUBLISHED);
        policy.requireCandidateType(candidate.getMemoryType(), candidate.getSourceType());

        AiCase sourceCase = null;
        if ("CASE".equals(normalize(candidate.getSourceType()))) {
            sourceCase = caseDao.queryByAgentAndCaseId(agentId, candidate.getSourceCaseId());
            policy.requireResolvedCase(candidate.getSourceType(), sourceCase == null ? "" : sourceCase.getStatus());
        }
        List<AgentMemoryEvidence> evidence = evidenceDao.queryByOwner(agentId, "CANDIDATE", candidateId);
        if (evidence == null || evidence.isEmpty()) throw new IllegalStateException("长期记忆候选缺少证据");
        if (evidence.stream().anyMatch(item -> !agentId.equals(item.getAgentId()))) {
            throw new IllegalArgumentException("证据不属于当前 Agent");
        }
        evidence.forEach(item -> {
            EvidenceInput input = new EvidenceInput(item.getSourceType(), item.getSourceId(), item.getSessionId(),
                    item.getMessageId(), item.getToolCallId(), item.getEvidenceQuote());
            validateEvidence(agentId, input);
            if (!sha256(resolveEvidenceContent(agentId, input)).equalsIgnoreCase(nonBlank(item.getContentHash(), ""))) {
                throw new IllegalStateException("证据原文已变化，必须重新生成候选后才能发布");
            }
        });

        AgentMemoryCard previous = cardDao.queryLatestByKey(agentId, candidate.getMemoryKey());
        String memoryId = previous == null ? UUID.randomUUID().toString() : previous.getMemoryId();
        int version = previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1;
        LocalDateTime now = LocalDateTime.now();
        cardDao.supersedeByKey(agentId, candidate.getMemoryKey());
        AgentMemoryCard card = AgentMemoryCard.builder()
                .memoryId(memoryId).agentId(agentId).memoryType(candidate.getMemoryType())
                .memoryKey(candidate.getMemoryKey()).version(version).title(candidate.getTitle())
                .description(candidate.getSummary()).contentJson(candidate.getContentJson()).status("PUBLISHED")
                .sourceCandidateId(candidateId).sourceCaseId(nonBlank(candidate.getSourceCaseId(), ""))
                .effectiveAt(now).publishedBy(publisher.trim()).publishedAt(now).createdAt(now).updatedAt(now).build();
        cardDao.insert(card);
        String cardEvidenceOwner = memoryId + ":v" + version;
        for (AgentMemoryEvidence item : evidence) {
            item.setId(null); item.setMemoryOwnerType("CARD"); item.setMemoryOwnerId(cardEvidenceOwner); item.setCreatedAt(now);
            evidenceDao.insertIgnore(item);
        }
        AgentMemoryIndexOutbox event = AgentMemoryIndexOutbox.builder()
                .eventId(UUID.randomUUID().toString()).agentId(agentId).memoryId(memoryId).memoryVersion(version)
                .eventType("UPSERT").payloadJson(JSON.toJSONString(card)).status("PENDING").attempts(0)
                .nextRetryAt(now).lastError("").createdAt(now).updatedAt(now).build();
        outboxDao.insert(event);
        if (previous != null && "PUBLISHED".equalsIgnoreCase(previous.getStatus())) {
            outboxDao.insert(deleteEvent(previous, "被新版本取代", now));
        }
        if (candidateDao.transition(agentId, candidateId, "APPROVED", "PUBLISHED",
                publisher.trim(), "发布长期记忆", null) != 1) {
            throw new IllegalStateException("候选发布状态发生并发变化");
        }
        rebuildProfile(agentId);
        return new PublishedMemory(memoryId, version, "PUBLISHED");
    }

    private void validateEvidence(String agentId, EvidenceInput item) {
        requireText(item.sourceType(), "evidence.sourceType");
        requireText(item.sourceId(), "evidence.sourceId");
        requireText(item.quote(), "evidence.quote");
        String original = resolveEvidenceContent(agentId, item);
        if (!original.contains(item.quote())) throw new IllegalArgumentException("证据引用必须来自原始记录");
    }

    private String resolveEvidenceContent(String agentId, EvidenceInput item) {
        if ("MESSAGE".equals(normalize(item.sourceType()))) {
            if (item.messageId() == null) throw new IllegalArgumentException("消息证据必须包含 messageId");
            if (!String.valueOf(item.messageId()).equals(item.sourceId().trim())) {
                throw new IllegalArgumentException("消息证据 sourceId 必须等于 messageId");
            }
            ChatMessage message = messageDao.queryById(item.messageId());
            if (message == null || !agentId.equals(message.getAgentId())) {
                throw new IllegalArgumentException("消息证据不属于当前 Agent");
            }
            if (item.sessionId() != null && !item.sessionId().isBlank()
                    && !item.sessionId().equals(message.getSessionId())) {
                throw new IllegalArgumentException("消息证据不属于当前会话");
            }
            return nonBlank(message.getContent(), "");
        }
        if ("CASE".equals(normalize(item.sourceType()))) {
            AiCase source = caseDao.queryByAgentAndCaseId(agentId, item.sourceId().trim());
            if (source == null) throw new IllegalArgumentException("Case 证据不属于当前 Agent");
            return String.join("\n", nonBlank(source.getTitle(), ""), nonBlank(source.getSummary(), ""),
                    nonBlank(source.getResolution(), ""));
        }
        throw new IllegalArgumentException("暂不支持的证据类型: " + normalize(item.sourceType()));
    }

    private AgentMemoryIndexOutbox deleteEvent(AgentMemoryCard card, String reason, LocalDateTime now) {
        return AgentMemoryIndexOutbox.builder().eventId(UUID.randomUUID().toString())
                .agentId(card.getAgentId()).memoryId(card.getMemoryId()).memoryVersion(card.getVersion())
                .eventType("DELETE").payloadJson(JSON.toJSONString(java.util.Map.of("reason", reason)))
                .status("PENDING").attempts(0).nextRetryAt(now).lastError("")
                .createdAt(now).updatedAt(now).build();
    }

    private void rebuildProfile(String agentId) {
        if (profileService != null) profileService.compileLatest(agentId);
    }

    private AgentMemoryEvidence toEvidence(String candidateId, String agentId, EvidenceInput item, LocalDateTime now) {
        return AgentMemoryEvidence.builder().memoryOwnerType("CANDIDATE").memoryOwnerId(candidateId)
                .agentId(agentId).sourceType(normalize(item.sourceType())).sourceId(item.sourceId().trim())
                .sessionId(nonBlank(item.sessionId(), "")).messageId(item.messageId())
                .toolCallId(nonBlank(item.toolCallId(), "")).evidenceQuote(item.quote().trim())
                .contentHash(sha256(resolveEvidenceContent(agentId, item))).createdAt(now).build();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }

    public record SubmitCandidate(String agentId, String memoryType, String memoryKey, String title,
                                  String summary, String contentJson, String sourceType, String sourceId,
                                  String sourceSessionId, String sourceCaseId, int confidence, String modelId,
                                  String promptVersion, List<EvidenceInput> evidence) { }
    public record EvidenceInput(String sourceType, String sourceId, String sessionId, Long messageId,
                                String toolCallId, String quote) { }
    public record PublishedMemory(String memoryId, int version, String status) { }
}
