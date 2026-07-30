package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.operations.ExplicitFeedbackRequest;
import cn.bugstack.ai.api.dto.operations.ManualFeedbackRequest;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IAiSignalDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseAuditDao;
import cn.bugstack.ai.infrastructure.dao.ICaseScoreSnapshotDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.CaseScoreSnapshot;
import cn.bugstack.ai.infrastructure.dao.po.CaseEvidence;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import cn.bugstack.ai.domain.agent.service.operations.WorkflowTransitionPolicy;
import cn.bugstack.ai.trigger.service.analysis.CaseMemoryPublisher;
import cn.bugstack.ai.trigger.service.analysis.AgentMemoryProfileService;
import cn.bugstack.ai.trigger.service.analysis.ConversationQualificationPolicy;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import cn.bugstack.ai.trigger.service.conversation.ConversationSessionService;
import cn.bugstack.ai.trigger.service.memory.LongTermMemoryRecallService;
import cn.bugstack.ai.trigger.service.memory.MemoryQueryAdmissionPolicy;
import cn.bugstack.ai.trigger.service.observability.ConversationTraceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/agents/{agentId}")
@Slf4j
public class AgentOperationsController {

    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IChatMessageDao chatMessageDao;
    @Resource private IAiLlmLogDao llmLogDao;
    @Resource private IAiSignalDao signalDao;
    @Resource private IMemorySummaryDao memorySummaryDao;
    @Resource private IMemoryStateDao memoryStateDao;
    @Resource private IMemoryToolResultDao memoryToolResultDao;
    @Resource private ICaseEvidenceDao caseEvidenceDao;
    @Resource private IAiCaseAuditDao caseAuditDao;
    @Resource private ICaseScoreSnapshotDao caseScoreSnapshotDao;
    @Resource private IAiSessionDao sessionDao;
    @Resource private CaseMemoryPublisher caseMemoryPublisher;
    @Resource private AgentMemoryProfileService agentMemoryProfileService;
    @Resource private FeedbackEvaluationJobQueue feedbackEvaluationJobQueue;
    @Resource private LongTermMemoryRecallService longTermMemoryRecallService;
    @Resource private LongTermMemoryPort longTermMemoryPort;
    @Resource private MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy;
    @Resource private ConversationSessionService conversationSessionService;
    @Resource private ConversationTraceService conversationTraceService;
    @Resource private AgentRuntimeBindingService agentRuntimeBindingService;
    private final WorkflowTransitionPolicy transitionPolicy = new WorkflowTransitionPolicy();
    private final ConversationQualificationPolicy qualificationPolicy = new ConversationQualificationPolicy();

    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@PathVariable("agentId") String agentId,
                                               @RequestBody ExplicitFeedbackRequest request) {
        request.validate();
        if ("THUMBS_UP".equals(request.normalizedType())
                && (request.message() == null || request.message().isBlank())
                && (request.correction() == null || request.correction().isBlank())) {
            return Map.of("success", true, "recorded", false, "reason", "helpful click is not business feedback");
        }
        ChatMessage target = chatMessageDao.queryById(request.assistantMessageId());
        if (target == null || !"assistant".equals(target.getRole())) {
            throw new IllegalArgumentException("Feedback target must be an assistant message");
        }
        if (!agentId.equals(target.getAgentId()) || !request.sessionId().equals(target.getSessionId())) {
            throw new IllegalArgumentException("Feedback target does not belong to this Agent and session");
        }
        LocalDateTime now = LocalDateTime.now();
        AiFeedback feedback = AiFeedback.builder()
                .sessionId(request.sessionId()).agentId(agentId)
                .assistantMessageId(request.assistantMessageId())
                .feedbackType(request.normalizedType()).rating(request.rating())
                .message(request.message() == null ? "" : request.message().trim())
                .correction(request.correction()).sourceType("EXPLICIT")
                .category("").matchedCaseId("").resolved(0).status("OPEN")
                .submittedBy(request.normalizedSubmittedBy()).createdAt(now).updatedAt(now)
                .build();
        feedbackDao.insert(feedback);
        return Map.of("success", true, "id", feedback.getId(), "sourceType", "EXPLICIT",
                "feedback", feedbackView(feedback));
    }

    @PostMapping("/feedback/manual")
    public Map<String, Object> submitManualFeedback(@PathVariable("agentId") String agentId,
                                                    @RequestBody ManualFeedbackRequest request) {
        request.validate();
        LocalDateTime now = LocalDateTime.now();
        AiFeedback feedback = AiFeedback.builder()
                .sessionId("").agentId(agentId).assistantMessageId(null)
                .feedbackType(request.normalizedFeedbackType()).rating(request.rating())
                .message(request.normalizedMessage()).correction("")
                .sourceType(request.normalizedSourceType())
                .category(request.normalizedCategory()).matchedCaseId("")
                .resolved(0).status("OPEN")
                .submittedBy(request.normalizedSubmittedBy())
                .createdAt(now).updatedAt(now)
                .build();
        feedbackDao.insert(feedback);
        try {
            feedbackEvaluationJobQueue.enqueue(agentId, feedback.getId());
        } catch (Exception exception) {
            log.warn("Failed to enqueue feedback evaluation for feedback {}", feedback.getId(), exception);
        }
        return Map.of("success", true, "id", feedback.getId(), "sourceType", feedback.getSourceType(),
                "feedback", feedbackView(feedback));
    }

    @GetMapping("/feedback")
    public List<Map<String, Object>> feedback(@PathVariable("agentId") String agentId,
                                              @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return feedbackDao.queryWorkspaceByAgentId(agentId, bounded(limit)).stream()
                .map(this::feedbackView)
                .toList();
    }

    @PostMapping("/feedback/{feedbackId}/transition")
    @Transactional
    public Map<String, Object> transitionFeedback(@PathVariable("agentId") String agentId,
                                                  @PathVariable("feedbackId") long feedbackId,
                                                  @RequestBody FeedbackTransitionRequest request) {
        if (request == null || blank(request.toStatus()) || blank(request.actor())) {
            throw new IllegalArgumentException("toStatus and actor are required");
        }
        AiFeedback item = feedbackDao.queryById(feedbackId);
        if (item == null || !agentId.equals(item.getAgentId())) {
            throw new IllegalArgumentException("Feedback does not belong to this Agent");
        }
        String toStatus = request.toStatus().trim().toUpperCase();
        transitionPolicy.requireAllowed(WorkflowTransitionPolicy.Resource.FEEDBACK, item.getStatus(), toStatus);
        String matchedCaseId = safe(request.matchedCaseId());
        if ("PROMOTED".equals(toStatus)) {
            ensureFeedbackEligibleForPromotion(item, safe(request.reason()));
            matchedCaseId = promoteFeedbackToCase(agentId, item, matchedCaseId, safe(request.reason()));
        }
        int resolved = "RESOLVED".equals(toStatus) || "PROMOTED".equals(toStatus) || "INVALID".equals(toStatus) ? 1 : 0;
        int changed = feedbackDao.transitionStatus(feedbackId, agentId, item.getStatus(), toStatus,
                safe(request.category()), matchedCaseId, resolved);
        if (changed != 1) throw new IllegalStateException("Feedback changed concurrently; refresh and retry");
        AiFeedback updated = feedbackDao.queryById(feedbackId);
        return Map.of("success", true, "feedbackId", feedbackId, "fromStatus", item.getStatus(), "toStatus", toStatus,
                "caseId", matchedCaseId, "feedback", feedbackView(updated == null ? item : updated));
    }

    @GetMapping("/sources/{messageId}")
    public Map<String, Object> source(@PathVariable("agentId") String agentId, @PathVariable Long messageId) {
        return sourceOf(agentId, messageId);
    }

    @GetMapping("/feedback/{feedbackId}/promotion-audit")
    public Map<String, Object> feedbackPromotionAudit(@PathVariable("agentId") String agentId,
                                                      @PathVariable("feedbackId") long feedbackId) {
        AiFeedback feedback = feedbackDao.queryById(feedbackId);
        if (feedback == null || !agentId.equals(feedback.getAgentId())) {
            throw new IllegalArgumentException("Feedback does not belong to this Agent");
        }
        PromotionReadiness readiness = promotionReadiness(feedback);
        String caseId = safe(feedback.getMatchedCaseId());
        Map<String, Object> linkedCase = caseId.isBlank() ? Map.of() : caseView(caseDao.queryByAgentAndCaseId(agentId, caseId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("feedback", feedbackView(feedback));
        result.put("readiness", Map.of(
                "eligible", readiness.eligible(),
                "label", readiness.label(),
                "reason", readiness.reason()
        ));
        result.put("scoreBreakdown", promotionScoreBreakdown(feedback, ""));
        result.put("linkedCase", linkedCase);
        result.put("scoreSnapshots", caseId.isBlank() ? List.of() : caseAuditDao.queryScoreSnapshots(agentId, caseId));
        result.put("reviews", caseId.isBlank() ? List.of() : caseAuditDao.queryReviews(agentId, caseId));
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    @GetMapping("/signals")
    public List<cn.bugstack.ai.infrastructure.dao.po.AiSignal> signals(@PathVariable("agentId") String agentId,
                                                                       @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return signalDao.queryByAgentId(agentId, bounded(limit));
    }

    @GetMapping("/memory")
    public List<cn.bugstack.ai.infrastructure.dao.po.MemorySummary> memory(@PathVariable("agentId") String agentId,
                                                                           @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return memorySummaryDao.queryByAgent(agentId, bounded(limit));
    }

    @GetMapping("/memory/profile")
    public Map<String, Object> memoryProfile(@PathVariable("agentId") String agentId) {
        var profile = agentMemoryProfileService.latest(agentId);
        return Map.of("agentId", agentId, "profile", profile == null ? Map.of() : profile);
    }

    @GetMapping("/memory/recall")
    public List<LongTermMemoryRecallService.MemoryRecallItem> memoryRecall(@PathVariable("agentId") String agentId,
                                                                           @RequestParam("query") String query,
                                                                           @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return longTermMemoryRecallService.recall(agentId, query, bounded(limit));
    }

    @GetMapping("/sessions/{sessionId}/memory")
    public Map<String, Object> sessionMemory(@PathVariable("agentId") String agentId, @PathVariable("sessionId") String sessionId) {
        var summary = memorySummaryDao.queryLatest(sessionId);
        if (summary != null && !agentId.equals(summary.getAgentId())) throw new IllegalArgumentException("Memory does not belong to Agent");
        return Map.of("summary", summary == null ? Map.of() : summary,
                "state", java.util.Optional.ofNullable(memoryStateDao.queryLatest(sessionId)).orElseGet(cn.bugstack.ai.infrastructure.dao.po.MemoryState::new),
                "toolResults", memoryToolResultDao.queryBySession(sessionId, 50));
    }

    @GetMapping("/cases")
    public List<Map<String, Object>> cases(@PathVariable("agentId") String agentId,
                                           @RequestParam(value = "status", defaultValue = "") String status,
                                           @RequestParam(value = "limit", defaultValue = "50") int limit) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        return caseDao.queryByAgentAndStatus(agentId, normalizedStatus, bounded(limit))
                .stream().map(this::caseView).toList();
    }

    @GetMapping("/cases/top")
    public List<Map<String, Object>> topCases(@PathVariable("agentId") String agentId,
                                              @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return caseDao.queryTopByAgent(agentId, bounded(limit))
                .stream().map(this::caseView).toList();
    }

    @GetMapping("/cases/{caseId}")
    public Map<String, Object> caseDetail(@PathVariable("agentId") String agentId, @PathVariable("caseId") String caseId) {
        AiCase item = caseDao.queryByAgentAndCaseId(agentId, caseId);
        if (item == null) throw new IllegalArgumentException("Case does not belong to this Agent");
        List<Map<String, Object>> evidence = caseAuditDao.queryEvidence(agentId, caseId)
                .stream().map(row -> {
                    Map<String, Object> evidenceRow = new java.util.HashMap<>(row);
                    Object messageId = evidenceRow.get("message_id");
                    if (messageId instanceof Number number) evidenceRow.put("source", sourceOf(agentId, number.longValue()));
                    return evidenceRow;
                }).toList();
        return Map.of(
                "case", caseView(item),
                "evidence", evidence,
                "scoreSnapshots", caseAuditDao.queryScoreSnapshots(agentId, caseId),
                "reviews", caseAuditDao.queryReviews(agentId, caseId));
    }

    @PostMapping("/cases/{caseId}/transition")
    @Transactional
    public Map<String, Object> transitionCase(@PathVariable("agentId") String agentId,
                                               @PathVariable("caseId") String caseId,
                                               @RequestBody CaseTransitionRequest request) {
        if (request == null || blank(request.toStatus()) || blank(request.actor())) {
            throw new IllegalArgumentException("toStatus and actor are required");
        }
        AiCase item = caseDao.queryByAgentAndCaseId(agentId, caseId);
        if (item == null) throw new IllegalArgumentException("Case does not belong to this Agent");
        String toStatus = request.toStatus().trim().toUpperCase();
        transitionPolicy.requireAllowed(WorkflowTransitionPolicy.Resource.CASE, item.getStatus(), toStatus);
        if ("IN_PROGRESS".equals(toStatus) && blank(request.owner())) {
            throw new IllegalArgumentException("owner is required when a Case enters IN_PROGRESS");
        }
        if ("RESOLVED".equals(toStatus) && blank(request.resolution())) {
            throw new IllegalArgumentException("resolution is required when a Case enters RESOLVED");
        }
        if (("IGNORED".equals(toStatus) || "ARCHIVED".equals(toStatus)) && blank(request.reason())) {
            throw new IllegalArgumentException("reason is required when a Case is ignored or archived");
        }
        boolean rollback = ("PENDING_REVIEW".equals(toStatus) && "CONFIRMED".equals(item.getStatus()))
                || ("CONFIRMED".equals(toStatus) && "IN_PROGRESS".equals(item.getStatus()))
                || ("IN_PROGRESS".equals(toStatus) && "RESOLVED".equals(item.getStatus()))
                || ("CONFIRMED".equals(toStatus) && "ARCHIVED".equals(item.getStatus()))
                || ("CANDIDATE".equals(toStatus) && !"CANDIDATE".equals(item.getStatus()));
        if (rollback && blank(request.reason())) {
            throw new IllegalArgumentException("reason is required when a Case is rolled back");
        }
        int changed = caseDao.transitionStatus(agentId, caseId, item.getStatus(), toStatus,
                safe(request.owner()), safe(request.resolution()));
        if (changed != 1) throw new IllegalStateException("Case changed concurrently; refresh and retry");
        caseAuditDao.insertReview(caseId, agentId, item.getStatus(), toStatus,
                request.actor().trim(), safe(request.reason()));
        AiCase updated = caseDao.queryByAgentAndCaseId(agentId, caseId);
        caseMemoryPublisher.publish(updated == null ? item : updated, toStatus, safe(request.reason()));
        return Map.of("success", true, "caseId", caseId, "fromStatus", item.getStatus(), "toStatus", toStatus);
    }

    @PostMapping("/cases/{caseId}/merge")
    @Transactional
    public Map<String, Object> mergeCase(@PathVariable("agentId") String agentId,
                                         @PathVariable("caseId") String caseId,
                                         @RequestBody CaseMergeRequest request) {
        if (request == null || blank(request.targetCaseId()) || blank(request.actor())) {
            throw new IllegalArgumentException("targetCaseId and actor are required");
        }
        String targetCaseId = request.targetCaseId().trim();
        if (caseId.equals(targetCaseId)) {
            throw new IllegalArgumentException("Case cannot merge into itself");
        }
        AiCase source = caseDao.queryByAgentAndCaseId(agentId, caseId);
        if (source == null) throw new IllegalArgumentException("Case does not belong to this Agent");
        AiCase target = caseDao.queryByAgentAndCaseId(agentId, targetCaseId);
        if (target == null) throw new IllegalArgumentException("Target Case does not belong to this Agent");
        transitionPolicy.requireAllowed(WorkflowTransitionPolicy.Resource.CASE, source.getStatus(), "MERGED");
        String reason = safe(request.reason());
        String resolution = "合并到 Case: " + targetCaseId + (reason.isEmpty() ? "" : "；原因：" + reason);
        int changed = caseDao.mergeTo(agentId, caseId, source.getStatus(), targetCaseId, resolution);
        if (changed != 1) throw new IllegalStateException("Case changed concurrently; refresh and retry");
        caseAuditDao.insertReview(caseId, agentId, source.getStatus(), "MERGED",
                request.actor().trim(), resolution);
        return Map.of("success", true, "caseId", caseId, "mergedToCaseId", targetCaseId);
    }

    @GetMapping("/workspace/stats")
    public Map<String, Object> stats(@PathVariable("agentId") String agentId) {
        long feedback = feedbackDao.countExplicitByAgentId(agentId);
        long negative = feedbackDao.countNegativeByAgentId(agentId);
        long aiObserved = feedbackDao.countAiObservedByAgentId(agentId);
        long readyForCase = feedbackDao.countReadyForCaseByAgentId(agentId);
        long candidateCases = caseDao.countByAgentAndStatus(agentId, "CANDIDATE");
        long reviewCases = caseDao.countByAgentAndStatus(agentId, "PENDING_REVIEW");
        long inProgressCases = caseDao.countByAgentAndStatus(agentId, "IN_PROGRESS");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("todayFeedback", feedbackDao.countExplicitTodayByAgentId(agentId));
        result.put("businessFeedback", feedback);
        result.put("explicitFeedback", feedback);
        result.put("negativeFeedback", negative);
        result.put("aiObservationCount", aiObserved);
        result.put("readyForCaseFeedback", readyForCase);
        result.put("satisfactionRate", feedback == 0 ? 0 : Math.round((feedback - negative) * 10000d / feedback) / 100d);
        result.put("totalCases", caseDao.countByAgent(agentId));
        result.put("candidateCases", candidateCases);
        result.put("pendingCases", reviewCases);
        result.put("highPriorityCases", inProgressCases);
        result.put("inProgressCases", inProgressCases);
        result.put("resolvedCases", caseDao.countByAgentAndStatus(agentId, "RESOLVED"));
        return result;
    }

    @GetMapping("/workspace/overview")
    public Map<String, Object> workspaceOverview(@PathVariable("agentId") String agentId,
                                                 @RequestParam(value = "feedbackLimit", defaultValue = "5") int feedbackLimit,
                                                 @RequestParam(value = "caseLimit", defaultValue = "5") int caseLimit,
                                                 @RequestParam(value = "memoryLimit", defaultValue = "3") int memoryLimit) {
        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("agentId", agentId);
        overview.put("stats", stats(agentId));
        overview.put("recentFeedback", feedback(agentId, bounded(feedbackLimit)));
        overview.put("topCases", topCases(agentId, bounded(caseLimit)));
        overview.put("candidateCases", cases(agentId, "CANDIDATE", bounded(caseLimit)));
        overview.put("memoryProfile", memoryProfile(agentId));
        overview.put("recentMemorySummaries", memory(agentId, bounded(memoryLimit)));
        overview.put("generatedAt", LocalDateTime.now());
        return overview;
    }

    @GetMapping("/workspace/review-queue")
    public Map<String, Object> reviewQueue(@PathVariable("agentId") String agentId,
                                           @RequestParam(value = "limit", defaultValue = "5") int limit) {
        int boundedLimit = bounded(limit);
        List<Map<String, Object>> feedbackItems = feedback(agentId, boundedLimit).stream()
                .filter(item -> {
                    String status = safe((String) item.get("status")).toUpperCase();
                    return Set.of("OPEN", "AI_EVALUATING", "NEED_MORE_INFO", "VALID", "CLUSTERED").contains(status);
                })
                .limit(boundedLimit)
                .toList();
        List<Map<String, Object>> candidateCaseItems = cases(agentId, "CANDIDATE", boundedLimit);
        List<Map<String, Object>> pendingCaseItems = cases(agentId, "PENDING_REVIEW", boundedLimit);
        List<Map<String, Object>> inProgressCaseItems = cases(agentId, "IN_PROGRESS", boundedLimit);
        List<Map<String, Object>> recentSessions = sessionDao.queryByAgentId(agentId, boundedLimit).stream()
                .map(this::sessionQueueView)
                .toList();

        Map<String, Object> queue = new java.util.LinkedHashMap<>();
        queue.put("agentId", agentId);
        queue.put("feedbackQueue", feedbackItems);
        queue.put("candidateCases", candidateCaseItems);
        queue.put("pendingReviewCases", pendingCaseItems);
        queue.put("inProgressCases", inProgressCaseItems);
        queue.put("recentSessions", recentSessions);
        queue.put("generatedAt", LocalDateTime.now());
        return queue;
    }

    @GetMapping("/workspace/memory-governance")
    public Map<String, Object> memoryGovernance(@PathVariable("agentId") String agentId,
                                                @RequestParam(value = "query", defaultValue = "") String query,
                                                @RequestParam(value = "limit", defaultValue = "5") int limit) {
        int boundedLimit = bounded(limit);
        var profile = agentMemoryProfileService.latest(agentId);
        List<cn.bugstack.ai.infrastructure.dao.po.MemorySummary> summaries = memorySummaryDao.queryByAgent(agentId, boundedLimit);
        List<Map<String, Object>> summaryReadiness = summaries.stream()
                .map(summary -> memorySummaryReadiness(summary))
                .toList();
        long eligibleSummaryCount = summaryReadiness.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("eligibleForLongTerm")))
                .count();

        MemoryQueryAdmissionPolicy.AdmissionDecision queryDecision = memoryQueryAdmissionPolicy.inspectRecallQuery(query);
        List<LongTermMemoryRecallService.MemoryRecallItem> recallPreview = queryDecision.allowed()
                ? longTermMemoryRecallService.recall(agentId, query, boundedLimit)
                : List.of();

        Map<String, Object> profileView = new LinkedHashMap<>();
        if (profile != null) {
            profileView.put("agentId", safe(profile.getAgentId()));
            profileView.put("version", profile.getVersion() == null ? 0 : profile.getVersion());
            profileView.put("sourceCaseCount", countCsv(profile.getSourceCaseIds()));
            profileView.put("updatedAt", profile.getUpdatedAt());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("provider", longTermMemoryProvider());
        result.put("readiness", memoryReadiness(profile != null, eligibleSummaryCount, queryDecision.allowed()));
        result.put("policy", Map.of(
                "recall", queryDecisionView(queryDecision),
                "summaryStorageThreshold", Map.of("minLength", 20, "minInformativeTokens", 2)
        ));
        result.put("profile", profileView);
        result.put("recentSummaryReadiness", summaryReadiness);
        result.put("recallPreview", recallPreview);
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> messages(@PathVariable("agentId") String agentId, @PathVariable("sessionId") String sessionId) {
        return chatMessageDao.queryBySessionId(sessionId).stream()
                .filter(message -> agentId.equals(message.getAgentId())).toList();
    }

    @GetMapping("/sessions/{sessionId}/workbench")
    public Map<String, Object> sessionWorkbench(@PathVariable("agentId") String agentId,
                                                @PathVariable("sessionId") String sessionId) {
        Map<String, Object> detail = conversationSessionService.detail(agentId, sessionId);
        ConversationTraceService.ConversationTrace trace = conversationTraceService.trace(agentId, sessionId);
        Map<String, Long> eventTypeCounts = trace.timeline().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ConversationTraceService.TimelineEvent::type,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));
        Map<String, Long> eventStatusCounts = trace.timeline().stream()
                .map(ConversationTraceService.TimelineEvent::status)
                .filter(status -> !safe(status).isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> value.toUpperCase(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("sessionId", sessionId);
        result.put("session", detail.getOrDefault("session", Map.of()));
        result.put("overview", detail.getOrDefault("overview", Map.of()));
        result.put("memory", detail.getOrDefault("memory", Map.of()));
        result.put("feedback", detail.getOrDefault("feedback", List.of()));
        result.put("cases", detail.getOrDefault("cases", List.of()));
        result.put("subagents", detail.getOrDefault("subagents", List.of()));
        result.put("messages", detail.getOrDefault("messages", List.of()));
        result.put("timeline", detail.getOrDefault("timeline", List.of()));
        result.put("traceSummary", trace.summary());
        result.put("traceTimeline", trace.timeline());
        result.put("eventTypeCounts", eventTypeCounts);
        result.put("eventStatusCounts", eventStatusCounts);
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    @GetMapping("/workspace/runtime-audit")
    public Map<String, Object> runtimeAudit(@PathVariable("agentId") String agentId,
                                            @RequestParam(value = "sessionLimit", defaultValue = "5") int sessionLimit,
                                            @RequestParam(value = "llmLimit", defaultValue = "10") int llmLimit) {
        int boundedSessionLimit = bounded(sessionLimit);
        int boundedLlmLimit = bounded(llmLimit);
        AgentRuntimeBindingService.AgentRuntimeBindings bindings = agentRuntimeBindingService.assemble(agentId, ".", false);
        List<AiSession> sessions = sessionDao.queryByAgentId(agentId, boundedSessionLimit);
        List<Map<String, Object>> recentSessions = sessions.stream()
                .map(this::sessionQueueView)
                .toList();
        List<Map<String, Object>> recentExecutions = sessions.stream()
                .map(session -> executionAuditView(agentId, safe(session.getSessionId())))
                .filter(item -> !item.isEmpty())
                .toList();
        List<Map<String, Object>> llmLogs = llmLogDao.queryByAgentId(agentId, boundedLlmLimit).stream()
                .map(this::llmAuditView)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("workspace", bindings.getWorkspace() == null ? "" : bindings.getWorkspace().toString());
        result.put("runtimeBindings", runtimeBindingsView(bindings));
        result.put("recentSessions", recentSessions);
        result.put("recentExecutions", recentExecutions);
        result.put("recentLlmCalls", llmLogs);
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "message", exception.getMessage()));
    }

    private int bounded(int limit) { return Math.max(1, Math.min(200, limit)); }

    private Map<String, Object> runtimeBindingsView(AgentRuntimeBindingService.AgentRuntimeBindings bindings) {
        Map<String, ReActToolAllowlistPolicy.ToolOption> toolOptionMap = ReActToolAllowlistPolicy.options().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReActToolAllowlistPolicy.ToolOption::toolId,
                        option -> option,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new));
        List<Map<String, Object>> skills = bindings.getSkillIds().stream()
                .map(skillId -> {
                    var metadata = bindings.getSkillMetadataById().get(skillId);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("skillId", skillId);
                    item.put("skillName", metadata == null ? skillId : firstNonBlank(metadata.getSkillName(), skillId));
                    item.put("description", metadata == null ? "" : firstNonBlank(metadata.getDescription(), ""));
                    item.put("runtimeAvailable", metadata != null);
                    item.put("runtimePath", ".ma/skills/" + skillId + "/SKILL.md");
                    return item;
                })
                .toList();
        List<Map<String, Object>> mcps = bindings.getMcpIds().stream()
                .map(mcpId -> bindings.getMcpTools().stream()
                        .filter(item -> mcpId.equals(item.getMcpId()))
                        .findFirst()
                        .map(item -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("mcpId", firstNonBlank(item.getMcpId(), mcpId));
                            row.put("mcpName", firstNonBlank(item.getMcpName(), mcpId));
                            row.put("transportType", firstNonBlank(item.getTransportType(), ""));
                            row.put("runtimeAvailable", true);
                            return row;
                        })
                        .orElseGet(() -> Map.of(
                                "mcpId", mcpId,
                                "mcpName", mcpId,
                                "transportType", "",
                                "runtimeAvailable", false
                        )))
                .toList();
        List<Map<String, Object>> explicitTools = bindings.getExplicitToolIds().stream()
                .map(toolId -> toolView(toolOptionMap.get(toolId), "agent_binding"))
                .filter(item -> !item.isEmpty())
                .toList();
        List<Map<String, Object>> effectiveTools = bindings.getEffectiveToolIds().stream()
                .map(toolId -> toolView(toolOptionMap.get(toolId),
                        impliedToolSource(toolId, bindings.getExplicitToolIds(), bindings.getSkillIds(), bindings.getMcpIds())))
                .filter(item -> !item.isEmpty())
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", safe(bindings.getAgent().getAgentId()));
        result.put("modelId", safe(bindings.getAgent().getModelId()));
        result.put("channel", safe(bindings.getAgent().getChannel()));
        result.put("explicitToolIds", bindings.getExplicitToolIds());
        result.put("effectiveToolIds", bindings.getEffectiveToolIds());
        result.put("skills", skills);
        result.put("mcps", mcps);
        result.put("explicitTools", explicitTools);
        result.put("effectiveTools", effectiveTools);
        return result;
    }

    private Map<String, Object> toolView(ReActToolAllowlistPolicy.ToolOption option, String source) {
        if (option == null) return Map.of();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolId", option.toolId());
        item.put("name", option.name());
        item.put("description", option.description());
        item.put("riskLevel", option.riskLevel());
        item.put("source", source);
        return item;
    }

    private Map<String, Object> executionAuditView(String agentId, String sessionId) {
        if (blank(sessionId)) return Map.of();
        Map<String, Object> detail = conversationSessionService.detail(agentId, sessionId);
        ConversationTraceService.ConversationTrace trace = conversationTraceService.trace(agentId, sessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) detail.getOrDefault("overview", Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("routeType", safe((String) overview.get("latestRouteType")));
        result.put("executionStatus", safe((String) overview.get("latestExecutionStatus")));
        result.put("modelId", safe((String) overview.get("latestModelId")));
        result.put("executionId", safe((String) overview.get("latestExecutionId")));
        result.put("toolCallCount", trace.summary().toolCalls());
        result.put("llmCallCount", trace.summary().llmCalls());
        result.put("feedbackCount", trace.summary().feedbackCount());
        result.put("caseCount", trace.summary().caseCount());
        result.put("hasFailure", trace.summary().hasFailure());
        result.put("latestExecutionAt", overview.get("latestExecutionAt"));
        result.put("latestExecutionStep", overview.get("latestExecutionStep"));
        result.put("todoCount", countTodoFromStateJson((String) overview.get("latestExecutionStateJson")));
        return result;
    }

    private Map<String, Object> llmAuditView(AiLlmLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", log.getId());
        item.put("sessionId", safe(log.getSessionId()));
        item.put("modelName", safe(log.getModelName()));
        item.put("mode", safe(log.getMode()));
        item.put("status", safe(log.getStatus()));
        item.put("durationMs", log.getDurationMs() == null ? 0 : log.getDurationMs());
        item.put("totalTokens", log.getTotalTokens() == null ? 0 : log.getTotalTokens());
        item.put("historyMsgCount", log.getHistoryMsgCount() == null ? 0 : log.getHistoryMsgCount());
        item.put("foldedMsgCount", log.getFoldedMsgCount() == null ? 0 : log.getFoldedMsgCount());
        item.put("systemPromptLen", log.getSystemPromptLen() == null ? 0 : log.getSystemPromptLen());
        item.put("userMessageLen", log.getUserMessageLen() == null ? 0 : log.getUserMessageLen());
        item.put("assistantResponseLen", log.getAssistantResponseLen() == null ? 0 : log.getAssistantResponseLen());
        item.put("errorMessage", safe(log.getErrorMessage()));
        item.put("createdAt", log.getCreatedAt());
        return item;
    }

    private int countTodoFromStateJson(String stateJson) {
        if (blank(stateJson)) return 0;
        try {
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(stateJson);
            com.alibaba.fastjson2.JSONArray todos = root == null ? null : root.getJSONArray("todos");
            return todos == null ? 0 : todos.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return blank(first) ? safe(fallback) : safe(first);
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private String impliedToolSource(String toolId, List<String> explicitToolIds, List<String> skillIds, List<String> mcpIds) {
        String normalized = firstNonBlank(toolId, "").trim().toLowerCase();
        if ((explicitToolIds == null ? List.<String>of() : explicitToolIds).contains(normalized)) return "agent_binding";
        if (ReActToolAllowlistPolicy.READ_FILE.equals(normalized) && skillIds != null && !skillIds.isEmpty()) return "skill_binding";
        if (ReActToolAllowlistPolicy.CALL_MCP_TOOL.equals(normalized) && mcpIds != null && !mcpIds.isEmpty()) return "mcp_binding";
        if (ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS.equals(normalized)
                && (explicitToolIds == null ? List.<String>of() : explicitToolIds).contains(ReActToolAllowlistPolicy.TASK)) {
            return "task_cascade";
        }
        return "agent_binding";
    }

    private Map<String, Object> memorySummaryReadiness(cn.bugstack.ai.infrastructure.dao.po.MemorySummary summary) {
        MemoryQueryAdmissionPolicy.AdmissionDecision decision =
                memoryQueryAdmissionPolicy.inspectSummary(summary == null ? "" : summary.getSummary());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sessionId", summary == null ? "" : safe(summary.getSessionId()));
        item.put("version", summary == null || summary.getVersion() == null ? 0 : summary.getVersion());
        item.put("status", summary == null ? "" : safe(summary.getStatus()));
        item.put("modelId", summary == null ? "" : safe(summary.getModelId()));
        item.put("summary", summary == null ? "" : safe(summary.getSummary()));
        item.put("createdAt", summary == null ? null : summary.getCreatedAt());
        item.put("eligibleForLongTerm", decision.allowed());
        item.put("decision", admissionDecisionView(decision));
        return item;
    }

    private Map<String, Object> queryDecisionView(MemoryQueryAdmissionPolicy.AdmissionDecision decision) {
        Map<String, Object> item = new LinkedHashMap<>(admissionDecisionView(decision));
        item.put("query", decision.normalizedText());
        return item;
    }

    private Map<String, Object> admissionDecisionView(MemoryQueryAdmissionPolicy.AdmissionDecision decision) {
        return Map.of(
                "allowed", decision.allowed(),
                "reasonCode", decision.reasonCode(),
                "informativeTokenCount", decision.informativeTokenCount(),
                "requiredInformativeTokenCount", decision.requiredInformativeTokenCount(),
                "length", decision.length()
        );
    }

    private String longTermMemoryProvider() {
        if (longTermMemoryPort == null) return "UNKNOWN";
        String beanName = safe(longTermMemoryPort.getClass().getSimpleName());
        if (beanName.isBlank() && longTermMemoryPort.getClass().getInterfaces().length > 0) {
            beanName = safe(longTermMemoryPort.getClass().getInterfaces()[0].getSimpleName());
        }
        String normalized = beanName.toLowerCase();
        if (normalized.contains("mem0")) return "MEM0";
        if (normalized.contains("pgvector")) return "PGVECTOR";
        if (normalized.contains("noop")) return "DISABLED";
        return beanName.isBlank() ? "UNKNOWN" : beanName;
    }

    private String memoryReadiness(boolean hasProfile, long eligibleSummaryCount, boolean recallEligible) {
        boolean enabled = !"DISABLED".equals(longTermMemoryProvider());
        if (enabled && (hasProfile || eligibleSummaryCount > 0)) return recallEligible ? "READY" : "WARMING_UP";
        if (hasProfile || eligibleSummaryCount > 0) return "PARTIAL";
        return "BOOTSTRAPPING";
    }

    private int countCsv(String value) {
        if (blank(value)) return 0;
        return (int) java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .count();
    }

    private void ensureFeedbackEligibleForPromotion(AiFeedback feedback, String reason) {
        String status = safe(feedback.getStatus()).toUpperCase();
        String sourceType = safe(feedback.getSourceType()).toUpperCase();
        boolean statusQualified = Set.of("VALID", "CLUSTERED").contains(status);
        boolean explicitSource = Set.of("EXPLICIT", "USER", "OPERATIONS").contains(sourceType);
        ConversationQualificationPolicy.CasePromotionInput input =
                new ConversationQualificationPolicy.CasePromotionInput(
                        1,
                        explicitNegativeFeedback(feedback),
                        promotionConfidence(statusQualified, explicitSource),
                        false,
                        businessRelevanceScore(feedback),
                        evidenceScore(feedback, reason),
                        false
                );
        if (!statusQualified && !explicitSource) {
            throw new IllegalArgumentException("当前反馈尚未通过评测，不能直接升级为 Case");
        }
        if (!qualificationPolicy.shouldPromoteCase(input)) {
            throw new IllegalArgumentException("当前反馈证据不足，不能直接升级为 Case，请先补充信息或等待更多业务佐证");
        }
    }

    private String promoteFeedbackToCase(String agentId, AiFeedback feedback, String requestedCaseId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        String caseId = requestedCaseId.isBlank() ? "case-feedback-" + feedback.getId() : requestedCaseId;
        AiCase existing = caseDao.queryByAgentAndCaseId(agentId, caseId);
        String message = safe(feedback.getMessage());
        String title = buildCaseTitle(message, feedback.getFeedbackType());
        String summary = message.isBlank() ? "由 Feedback 人工升级生成的候选 Case" : message;
        if (existing == null) {
            AiCase record = AiCase.builder()
                    .caseId(caseId)
                    .agentId(agentId)
                    .title(title)
                    .summary(summary)
                    .caseType("QUALITY")
                    .severity("MEDIUM")
                    .frequency(1)
                    .affectedSessions(feedback.getSessionId() == null || feedback.getSessionId().isBlank() ? 0 : 1)
                    .importanceScore(65d)
                    .confidence("AI_INFERRED".equals(feedback.getSourceType()) ? 70d : 85d)
                    .totalScore("AI_INFERRED".equals(feedback.getSourceType()) ? 58d : 72d)
                    .status("CANDIDATE")
                    .skillId("")
                    .sourceModel("")
                    .extractionReason(reason.isBlank() ? "由 Feedback 人工升级为候选 Case" : reason)
                    .owner("")
                    .resolution("")
                    .lastSeenAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            caseDao.insert(record);
        } else {
            caseDao.incrementFrequency(caseId);
        }
        caseEvidenceDao.insertIgnore(CaseEvidence.builder()
                .caseId(caseId)
                .agentId(agentId)
                .evidenceType("FEEDBACK")
                .evidenceId(feedback.getId())
                .sessionId(safe(feedback.getSessionId()))
                .messageId(feedback.getAssistantMessageId())
                .excerpt(summary.substring(0, Math.min(summary.length(), 500)))
                .createdAt(now)
                .build());
        persistPromotionSnapshot(caseId, agentId, feedback, reason, now);
        caseAuditDao.insertReview(caseId, agentId,
                existing == null ? "NEW" : safe(existing.getStatus()),
                "PROMOTED",
                firstNonBlank(safe(feedback.getSubmittedBy()), "system"),
                buildPromotionReviewReason(feedback, reason));
        return caseId;
    }

    private void persistPromotionSnapshot(String caseId, String agentId, AiFeedback feedback, String reason, LocalDateTime now) {
        Map<String, Object> breakdown = promotionScoreBreakdown(feedback, reason);
        caseScoreSnapshotDao.insert(CaseScoreSnapshot.builder()
                .caseId(caseId)
                .agentId(agentId)
                .totalScore(numberValue(breakdown.get("totalScore")))
                .severityScore(numberValue(breakdown.get("severityScore")))
                .negativeFeedbackScore(numberValue(breakdown.get("negativeFeedbackScore")))
                .frequencyScore(numberValue(breakdown.get("frequencyScore")))
                .importanceScore(numberValue(breakdown.get("importanceScore")))
                .recencyScore(numberValue(breakdown.get("recencyScore")))
                .unresolvedAgeScore(numberValue(breakdown.get("unresolvedAgeScore")))
                .confidenceScore(numberValue(breakdown.get("confidenceScore")))
                .priorityFloorApplied(boolValue(breakdown.get("priorityFloorApplied")) ? 1 : 0)
                .rationale(safe((String) breakdown.get("rationale")))
                .createdAt(now)
                .build());
    }

    private String buildCaseTitle(String message, String feedbackType) {
        if (!message.isBlank()) {
            String compact = message.replaceAll("\\s+", " ").trim();
            return compact.length() <= 60 ? compact : compact.substring(0, 60);
        }
        return "Feedback 升级 Case：" + safe(feedbackType);
    }

    private int explicitNegativeFeedback(AiFeedback feedback) {
        if (feedback == null) return 0;
        if (feedback.getRating() != null && feedback.getRating() <= 2) return 1;
        return Set.of("THUMBS_DOWN", "NEGATIVE", "ISSUE_REPORT").contains(safe(feedback.getFeedbackType()).toUpperCase()) ? 1 : 0;
    }

    private String buildPromotionReviewReason(AiFeedback feedback, String reason) {
        String normalizedReason = safe(reason);
        if (!normalizedReason.isBlank()) return normalizedReason;
        return "Feedback 晋升为 Case；readiness=" + promotionReadiness(feedback).label()
                + "；依据=" + safe(feedback.getMessage());
    }

    private double promotionConfidence(boolean statusQualified, boolean explicitSource) {
        if (statusQualified) return 85d;
        if (explicitSource) return 78d;
        return 60d;
    }

    private Map<String, Object> promotionScoreBreakdown(AiFeedback feedback, String reason) {
        String status = safe(feedback.getStatus()).toUpperCase();
        boolean qualifiedStatus = Set.of("VALID", "CLUSTERED").contains(status);
        boolean explicitSource = isExplicitSource(feedback);
        double confidenceScore = promotionConfidence(qualifiedStatus, explicitSource);
        double negativeFeedbackScore = explicitNegativeFeedback(feedback) == 1 ? 90d : 35d;
        double importanceScore = businessRelevanceScore(feedback);
        double severityScore = severityScore(feedback);
        double frequencyScore = frequencyScore(feedback);
        double recencyScore = 80d;
        double unresolvedAgeScore = 60d;
        boolean priorityFloorApplied = qualifiedStatus && explicitSource;
        double totalScore = (severityScore * 0.16d)
                + (negativeFeedbackScore * 0.18d)
                + (frequencyScore * 0.12d)
                + (importanceScore * 0.18d)
                + (recencyScore * 0.08d)
                + (unresolvedAgeScore * 0.08d)
                + (confidenceScore * 0.20d);
        if (priorityFloorApplied) totalScore = Math.max(totalScore, 72d);
        String rationale = "状态=" + status
                + "，来源=" + safe(feedback.getSourceType())
                + "，业务相关度=" + (int) importanceScore
                + "，证据强度=" + (int) evidenceScore(feedback, reason)
                + "，置信度=" + (int) confidenceScore
                + (blank(reason) ? "" : "，晋升说明=" + safe(reason));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", round2(totalScore));
        result.put("severityScore", round2(severityScore));
        result.put("negativeFeedbackScore", round2(negativeFeedbackScore));
        result.put("frequencyScore", round2(frequencyScore));
        result.put("importanceScore", round2(importanceScore));
        result.put("recencyScore", round2(recencyScore));
        result.put("unresolvedAgeScore", round2(unresolvedAgeScore));
        result.put("confidenceScore", round2(confidenceScore));
        result.put("priorityFloorApplied", priorityFloorApplied);
        result.put("rationale", rationale);
        result.put("evidenceScore", round2(evidenceScore(feedback, reason)));
        return result;
    }

    private double severityScore(AiFeedback feedback) {
        String text = (safe(feedback.getMessage()) + " " + safe(feedback.getCorrection())).toLowerCase();
        if (containsAny(text, "无法下单", "支付失败", "订单丢失", "库存不一致", "缓存不一致", "数据错误")) return 88d;
        if (containsAny(text, "缺货", "补货", "查不到", "异常", "错误")) return 72d;
        return 55d;
    }

    private double frequencyScore(AiFeedback feedback) {
        String text = (safe(feedback.getMessage()) + " " + safe(feedback.getCorrection())).toLowerCase();
        if (containsAny(text, "一直", "经常", "反复", "每次", "总是", "长期")) return 78d;
        return 48d;
    }

    private double businessRelevanceScore(AiFeedback feedback) {
        String text = (safe(feedback.getMessage()) + " " + safe(feedback.getCategory())).toLowerCase();
        return containsAny(text, "订单", "商品", "库存", "缓存", "支付", "退款", "数据库", "db", "接口", "用户",
                "物流", "价格", "显卡", "业务", "内存", "sku", "页面", "补货", "缺货", "不一致") ? 90d : 40d;
    }

    private double evidenceScore(AiFeedback feedback, String reason) {
        String text = (safe(feedback.getMessage()) + " " + safe(reason) + " " + safe(feedback.getCorrection())).toLowerCase();
        double score = 35d;
        if (containsAny(text, "型号", "id", "sku", "订单号", "页面", "接口", "显卡", "内存", "ddr", "截图", "日志", "下单")) score += 35d;
        if (text.matches(".*\\d{2,}.*")) score += 20d;
        if (text.length() >= 18) score += 10d;
        return Math.min(score, 100d);
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase())) return true;
        }
        return false;
    }

    private Map<String, Object> sourceOf(String agentId, Long messageId) {
        ChatMessage message = chatMessageDao.queryById(messageId);
        if (message == null || !agentId.equals(message.getAgentId())) throw new IllegalArgumentException("Message source does not belong to this Agent");
        String content = message.getContent() == null ? "" : message.getContent().replaceAll("\\s+", " ").trim();
        return Map.of("agentId", agentId, "sessionId", message.getSessionId(), "messageId", message.getId(), "role", message.getRole(), "preview", content.substring(0, Math.min(content.length(), 240)));
    }

    private Map<String, Object> caseView(AiCase item) {
        if (item == null) return Map.of();
        String status = safe(item.getStatus()).toUpperCase();
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("caseId", safe(item.getCaseId()));
        view.put("agentId", safe(item.getAgentId()));
        view.put("title", safe(item.getTitle()));
        view.put("summary", safe(item.getSummary()));
        view.put("caseType", safe(item.getCaseType()));
        view.put("severity", safe(item.getSeverity()));
        view.put("frequency", item.getFrequency() == null ? 0 : item.getFrequency());
        view.put("affectedSessions", item.getAffectedSessions() == null ? 0 : item.getAffectedSessions());
        view.put("importanceScore", item.getImportanceScore());
        view.put("confidence", item.getConfidence());
        view.put("totalScore", item.getTotalScore());
        view.put("status", status);
        view.put("statusLabel", rawCaseStatusLabel(status));
        view.put("skillId", safe(item.getSkillId()));
        view.put("sourceModel", safe(item.getSourceModel()));
        view.put("extractionReason", safe(item.getExtractionReason()));
        view.put("owner", safe(item.getOwner()));
        view.put("resolution", safe(item.getResolution()));
        view.put("mergedToCaseId", safe(item.getMergedToCaseId()));
        view.put("lastSeenAt", item.getLastSeenAt());
        view.put("createdAt", item.getCreatedAt());
        view.put("updatedAt", item.getUpdatedAt());
        view.put("availableActions", caseAvailableActions(status));
        return view;
    }

    private Map<String, Object> sessionQueueView(AiSession item) {
        if (item == null) return Map.of();
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("sessionId", safe(item.getSessionId()));
        view.put("agentId", safe(item.getAgentId()));
        view.put("title", safe(item.getTitle()));
        view.put("preview", safe(item.getPreview()));
        view.put("modelId", safe(item.getModelId()));
        view.put("messageCount", item.getMessageCount() == null ? 0 : item.getMessageCount());
        view.put("status", item.getStatus() == null || item.getStatus() == 1 ? "ACTIVE" : "DELETED");
        view.put("createdAt", item.getCreatedAt());
        view.put("updatedAt", item.getUpdatedAt());
        view.put("lastMessageAt", item.getLastMessageAt());
        return view;
    }

    private List<Map<String, Object>> caseAvailableActions(String status) {
        return switch (safe(status).toUpperCase()) {
            case "CANDIDATE" -> List.of(
                    action("PENDING_REVIEW", "提交审核"),
                    action("IGNORED", "驳回问题"),
                    action("MERGED", "合并到其他 Case", "MERGE"));
            case "PENDING_REVIEW" -> List.of(
                    action("CONFIRMED", "确认案件"),
                    action("IGNORED", "驳回问题"),
                    action("CANDIDATE", "退回候选"));
            case "CONFIRMED" -> List.of(
                    action("IN_PROGRESS", "开始处理"),
                    action("PENDING_REVIEW", "退回待审核"));
            case "IN_PROGRESS" -> List.of(
                    action("RESOLVED", "标记已解决"),
                    action("CONFIRMED", "退回已确认"));
            case "RESOLVED" -> List.of(
                    action("IN_PROGRESS", "重新处理"),
                    action("ARCHIVED", "归档"));
            case "ARCHIVED" -> List.of(action("CONFIRMED", "恢复跟踪"));
            case "IGNORED", "MERGED" -> List.of(action("CANDIDATE", "重新提交"));
            default -> List.of();
        };
    }

    private String rawCaseStatusLabel(String value) {
        return switch (safe(value).toUpperCase()) {
            case "CANDIDATE" -> "候选问题";
            case "PENDING_REVIEW" -> "待人工审核";
            case "CONFIRMED" -> "已确认案件";
            case "IN_PROGRESS" -> "处理中";
            case "RESOLVED" -> "已解决";
            case "ARCHIVED" -> "已归档";
            case "IGNORED" -> "已驳回";
            case "MERGED" -> "已合并";
            default -> safe(value);
        };
    }

    private Map<String, Object> feedbackView(AiFeedback item) {
        if (item == null) return Map.of();
        String status = safe(item.getStatus()).toUpperCase();
        String aiStatus = switch (status) {
            case "OPEN" -> "NEW";
            case "AI_EVALUATING" -> "AI_EVALUATING";
            case "NEED_MORE_INFO" -> "NEED_MORE_INFO";
            case "INVALID" -> "AI_INVALID";
            default -> "AI_VALID";
        };
        String reviewStatus = switch (status) {
            case "OPEN", "AI_EVALUATING" -> "PENDING_AI";
            case "NEED_MORE_INFO" -> "WAITING_USER";
            case "INVALID" -> "REJECTED";
            case "PROMOTED" -> "PROMOTED";
            case "RESOLVED" -> "CLOSED";
            default -> "PENDING_REVIEW";
        };
        String promotionStatus = switch (status) {
            case "PROMOTED", "RESOLVED" -> "PROMOTED";
            case "VALID", "CLUSTERED" -> "READY_FOR_CASE";
            case "INVALID" -> "NOT_ELIGIBLE";
            default -> "NOT_PROMOTED";
        };
        PromotionReadiness promotionReadiness = promotionReadiness(item);
        String sourceLabel = switch (safe(item.getSourceType()).toUpperCase()) {
            case "EXPLICIT", "USER" -> "用户反馈";
            case "OPERATIONS" -> "运维反馈";
            case "TEST" -> "测试反馈";
            case "AI_INFERRED" -> "AI观察";
            default -> blank(item.getSourceType()) ? "未知来源" : item.getSourceType();
        };
        String statusLabel = switch (status) {
            case "OPEN" -> "新反馈";
            case "AI_EVALUATING" -> "AI评测中";
            case "NEED_MORE_INFO" -> "需要补充信息";
            case "INVALID" -> "无效反馈";
            case "VALID" -> "待人工审核";
            case "CLUSTERED" -> "待升级Case";
            case "PROMOTED" -> "已升级为Case";
            case "RESOLVED" -> "已关闭";
            default -> status;
        };
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("sessionId", safe(item.getSessionId()));
        view.put("agentId", safe(item.getAgentId()));
        view.put("assistantMessageId", item.getAssistantMessageId());
        view.put("feedbackType", safe(item.getFeedbackType()));
        view.put("rating", item.getRating() == null ? 0 : item.getRating());
        view.put("message", safe(item.getMessage()));
        view.put("correction", safe(item.getCorrection()));
        view.put("sourceType", safe(item.getSourceType()));
        view.put("sourceLabel", sourceLabel);
        view.put("category", safe(item.getCategory()));
        view.put("matchedCaseId", safe(item.getMatchedCaseId()));
        view.put("resolved", item.getResolved() == null ? 0 : item.getResolved());
        view.put("status", status);
        view.put("statusLabel", statusLabel);
        view.put("aiStatus", aiStatus);
        view.put("aiStatusLabel", labelStatus(aiStatus));
        view.put("reviewStatus", reviewStatus);
        view.put("reviewStatusLabel", labelStatus(reviewStatus));
        view.put("promotionStatus", promotionStatus);
        view.put("promotionStatusLabel", labelStatus(promotionStatus));
        view.put("promotionEligible", promotionReadiness.eligible());
        view.put("promotionReadinessLabel", promotionReadiness.label());
        view.put("promotionReadinessReason", promotionReadiness.reason());
        view.put("submittedBy", safe(item.getSubmittedBy()));
        view.put("createdAt", item.getCreatedAt());
        view.put("updatedAt", item.getUpdatedAt());
        view.put("qualificationHint", feedbackQualificationHint(item));
        view.put("evaluationReason", feedbackEvaluationReason(item));
        view.put("nextAction", feedbackNextAction(item));
        view.put("availableActions", feedbackAvailableActions(item, promotionReadiness));
        return view;
    }

    private String feedbackQualificationHint(AiFeedback item) {
        String status = safe(item.getStatus()).toUpperCase();
        return switch (status) {
            case "OPEN" -> "已记录，待进入评测或人工确认。";
            case "AI_EVALUATING" -> "评测中，正在判断是否属于当前 Agent 的业务反馈。";
            case "NEED_MORE_INFO" -> "信息不足，建议补充型号、页面、订单号、截图或复现场景。";
            case "VALID" -> "已判定为有效业务反馈，可进一步聚类或升级为 Case。";
            case "CLUSTERED" -> "已进入候选问题簇，适合与同类反馈合并升级。";
            case "PROMOTED" -> "已完成晋升，进入 Case 工作流。";
            case "RESOLVED" -> "反馈流程已关闭。";
            case "INVALID" -> "已判定为无效或暂不纳入处理。";
            default -> "当前反馈正在流转中。";
        };
    }

    private String feedbackEvaluationReason(AiFeedback item) {
        String status = safe(item.getStatus()).toUpperCase();
        String category = safe(item.getCategory());
        String prefix = category.isBlank() ? "" : "分类：" + category + "。";
        String evidence = feedbackEvidenceSummary(item);
        return switch (status) {
            case "NEED_MORE_INFO" -> prefix + evidence + " 已识别到业务问题方向，但缺少足够上下文，暂时不能稳定升级为 Case。";
            case "VALID" -> prefix + evidence + " 已同时具备问题描述、业务对象和基础证据，可以进入待升级或直接转 Case。";
            case "PROMOTED" -> prefix + evidence + " 当前反馈已满足升级条件，已进入 Case 工作流。";
            case "INVALID" -> prefix + evidence + " 当前描述更像测试语句、问候，或尚未形成明确业务问题，因此暂不纳入有效反馈。";
            case "AI_EVALUATING" -> prefix + evidence + " 系统正在确认它是否属于当前 Agent 的业务范围，以及是否达到升级阈值。";
            default -> prefix + evidence;
        };
    }

    private String feedbackNextAction(AiFeedback item) {
        String status = safe(item.getStatus()).toUpperCase();
        return switch (status) {
            case "NEED_MORE_INFO" -> "建议补充商品型号、页面位置、订单号、截图或稳定复现场景。";
            case "VALID" -> "建议确认是否与历史同类反馈合并，再决定是否升级为 Case。";
            case "PROMOTED" -> "建议进入 Case 审核、指派负责人并补充处理进展。";
            case "INVALID" -> "如果这是真实业务问题，请补充具体业务对象和异常表现后重新提交。";
            default -> "建议按照当前反馈状态继续流转处理。";
        };
    }

    private String feedbackEvidenceSummary(AiFeedback item) {
        String text = safe(item.getMessage()).toLowerCase();
        java.util.ArrayList<String> evidence = new java.util.ArrayList<>();
        if (text.matches(".*\\d{2,}.*")) evidence.add("包含数字线索");
        if (containsAny(text, "sku", "型号", "model", "ddr", "显卡", "内存", "品牌", "id")) evidence.add("包含商品或型号线索");
        if (containsAny(text, "页面", "列表", "详情", "接口", "api", "下单", "库存", "支付", "订单")) evidence.add("包含业务位置线索");
        if (containsAny(text, "截图", "日志", "报错", "异常", "不一致", "失败", "超时", "缺货", "补货", "缺失")) evidence.add("包含问题证据线索");
        if (evidence.isEmpty()) return "暂未识别到足够证据线索。";
        return "已识别" + String.join("、", evidence) + "。";
    }

    private List<Map<String, Object>> feedbackAvailableActions(AiFeedback item, PromotionReadiness promotionReadiness) {
        String status = safe(item == null ? "" : item.getStatus()).toUpperCase();
        boolean promotionAllowed = promotionReadiness != null && promotionReadiness.eligible();
        return switch (safe(status).toUpperCase()) {
            case "OPEN" -> List.of(
                    action("AI_EVALUATING", "提交 AI 评测"),
                    action("INVALID", "标记无效"));
            case "AI_EVALUATING" -> List.of(
                    action("VALID", "确认可进入升级判断"),
                    action("NEED_MORE_INFO", "要求补充信息"),
                    action("INVALID", "标记无效"));
            case "NEED_MORE_INFO" -> List.of(
                    action("AI_EVALUATING", "补充后重新评测"),
                    action("INVALID", "标记无效"));
            case "VALID" -> promotionAllowed
                    ? List.of(
                    action("PROMOTED", "升级为 Case"),
                    action("CLUSTERED", "进入候选问题簇"),
                    action("INVALID", "判定无效"))
                    : List.of(
                    action("CLUSTERED", "进入候选问题簇"),
                    action("INVALID", "判定无效"));
            case "CLUSTERED" -> promotionAllowed
                    ? List.of(
                    action("PROMOTED", "升级为 Case"),
                    action("VALID", "退回升级判断"),
                    action("INVALID", "判定无效"))
                    : List.of(
                    action("VALID", "退回升级判断"),
                    action("INVALID", "判定无效"));
            case "INVALID" -> List.of(action("OPEN", "重新打开"));
            case "PROMOTED" -> List.of(action("RESOLVED", "关闭反馈"));
            case "RESOLVED" -> List.of(action("OPEN", "重新打开"));
            default -> List.of();
        };
    }

    private Map<String, Object> action(String status, String label) {
        return action(status, label, "TRANSITION");
    }

    private Map<String, Object> action(String status, String label, String operation) {
        return Map.of("status", status, "label", label, "operation", operation);
    }

    private PromotionReadiness promotionReadiness(AiFeedback item) {
        String status = safe(item.getStatus()).toUpperCase();
        boolean qualifiedStatus = Set.of("VALID", "CLUSTERED").contains(status);
        double businessScore = businessRelevanceScore(item);
        double evidenceScore = evidenceScore(item, "");
        int negativeFeedback = explicitNegativeFeedback(item);
        boolean eligible = qualificationPolicy.shouldPromoteCase(new ConversationQualificationPolicy.CasePromotionInput(
                1,
                negativeFeedback,
                promotionConfidence(qualifiedStatus, isExplicitSource(item)),
                false,
                businessScore,
                evidenceScore,
                false
        ));
        if (eligible) {
            return new PromotionReadiness(true, "满足升级条件", "当前反馈已通过状态校验，且业务相关性与证据强度达到升级 Case 的阈值。");
        }
        if (!qualifiedStatus) {
            return new PromotionReadiness(false, "还不能升级", "当前仍处于“" + rawFeedbackStatusLabel(item.getStatus()) + "”，需要先完成 AI 评测并进入“已判定有效/待升级Case”。");
        }
        if (businessScore < 70d) {
            return new PromotionReadiness(false, "业务相关性不足", "当前描述还没有稳定落到明确业务对象，建议补充具体商品、页面、订单或接口上下文。");
        }
        if (evidenceScore < 60d) {
            return new PromotionReadiness(false, "证据不足", "当前缺少足够证据，建议补充型号、订单号、截图、日志或稳定复现场景。");
        }
        return new PromotionReadiness(false, "建议继续聚类", "虽然已判定为有效反馈，但单次证据仍偏弱，建议与同类反馈聚类后再升级为 Case。");
    }

    private boolean isExplicitSource(AiFeedback item) {
        return Set.of("EXPLICIT", "USER", "OPERATIONS").contains(safe(item.getSourceType()).toUpperCase());
    }

    private String labelStatus(String value) {
        return switch (safe(value).toUpperCase()) {
            case "NEW" -> "新反馈";
            case "AI_EVALUATING" -> "AI评测中";
            case "NEED_MORE_INFO" -> "需要补充信息";
            case "AI_VALID" -> "评测通过";
            case "AI_INVALID" -> "评测不通过";
            case "PENDING_AI" -> "待AI评测";
            case "WAITING_USER" -> "等待补充";
            case "PENDING_REVIEW" -> "待人工审核";
            case "PROMOTED" -> "已升级Case";
            case "READY_FOR_CASE" -> "可升级Case";
            case "NOT_PROMOTED" -> "未升级";
            case "NOT_ELIGIBLE" -> "不可升级";
            case "REJECTED" -> "已驳回";
            case "CLOSED" -> "已关闭";
            default -> safe(value);
        };
    }

    private String rawFeedbackStatusLabel(String value) {
        return switch (safe(value).toUpperCase()) {
            case "OPEN" -> "新反馈";
            case "AI_EVALUATING" -> "AI评测中";
            case "NEED_MORE_INFO" -> "需要补充信息";
            case "VALID" -> "已判定有效";
            case "CLUSTERED" -> "待升级Case";
            case "PROMOTED" -> "已升级Case";
            case "INVALID" -> "无效反馈";
            case "RESOLVED" -> "已关闭";
            default -> safe(value);
        };
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private record PromotionReadiness(boolean eligible, String label, String reason) {}

    public record CaseTransitionRequest(String toStatus, String actor, String reason, String owner, String resolution) {}
    public record CaseMergeRequest(String targetCaseId, String actor, String reason) {}
    public record FeedbackTransitionRequest(String toStatus, String actor, String reason, String category, String matchedCaseId) {}
}
