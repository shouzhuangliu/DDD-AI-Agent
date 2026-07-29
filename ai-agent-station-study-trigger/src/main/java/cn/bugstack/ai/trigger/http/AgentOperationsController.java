package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.operations.ExplicitFeedbackRequest;
import cn.bugstack.ai.api.dto.operations.ManualFeedbackRequest;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IAiSignalDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseAuditDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.CaseEvidence;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.domain.agent.service.operations.WorkflowTransitionPolicy;
import cn.bugstack.ai.trigger.service.analysis.CaseMemoryPublisher;
import cn.bugstack.ai.trigger.service.analysis.AgentMemoryProfileService;
import cn.bugstack.ai.trigger.service.analysis.ConversationQualificationPolicy;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
    @Resource private IAiSignalDao signalDao;
    @Resource private IMemorySummaryDao memorySummaryDao;
    @Resource private IMemoryStateDao memoryStateDao;
    @Resource private IMemoryToolResultDao memoryToolResultDao;
    @Resource private ICaseEvidenceDao caseEvidenceDao;
    @Resource private IAiCaseAuditDao caseAuditDao;
    @Resource private CaseMemoryPublisher caseMemoryPublisher;
    @Resource private AgentMemoryProfileService agentMemoryProfileService;
    @Resource private FeedbackEvaluationJobQueue feedbackEvaluationJobQueue;
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

    @GetMapping("/sessions/{sessionId}/memory")
    public Map<String, Object> sessionMemory(@PathVariable("agentId") String agentId, @PathVariable("sessionId") String sessionId) {
        var summary = memorySummaryDao.queryLatest(sessionId);
        if (summary != null && !agentId.equals(summary.getAgentId())) throw new IllegalArgumentException("Memory does not belong to Agent");
        return Map.of("summary", summary == null ? Map.of() : summary,
                "state", java.util.Optional.ofNullable(memoryStateDao.queryLatest(sessionId)).orElseGet(cn.bugstack.ai.infrastructure.dao.po.MemoryState::new),
                "toolResults", memoryToolResultDao.queryBySession(sessionId, 50));
    }

    @GetMapping("/cases")
    public List<AiCase> cases(@PathVariable("agentId") String agentId,
                              @RequestParam(value = "status", defaultValue = "") String status,
                              @RequestParam(value = "limit", defaultValue = "50") int limit) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        return caseDao.queryByAgentAndStatus(agentId, normalizedStatus, bounded(limit));
    }

    @GetMapping("/cases/top")
    public List<AiCase> topCases(@PathVariable("agentId") String agentId,
                                 @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return caseDao.queryTopByAgent(agentId, bounded(limit));
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
                "case", item,
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

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> messages(@PathVariable("agentId") String agentId, @PathVariable("sessionId") String sessionId) {
        return chatMessageDao.queryBySessionId(sessionId).stream()
                .filter(message -> agentId.equals(message.getAgentId())).toList();
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
        return caseId;
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

    private double promotionConfidence(boolean statusQualified, boolean explicitSource) {
        if (statusQualified) return 85d;
        if (explicitSource) return 78d;
        return 60d;
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
