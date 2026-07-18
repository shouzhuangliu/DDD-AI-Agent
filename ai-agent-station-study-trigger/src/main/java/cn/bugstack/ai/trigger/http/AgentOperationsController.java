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
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.domain.agent.service.operations.WorkflowTransitionPolicy;
import cn.bugstack.ai.trigger.service.analysis.CaseMemoryPublisher;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/agents/{agentId}")
public class AgentOperationsController {

    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IChatMessageDao chatMessageDao;
    @Resource private IAiSignalDao signalDao;
    @Resource private IMemorySummaryDao memorySummaryDao;
    @Resource private IMemoryStateDao memoryStateDao;
    @Resource private IMemoryToolResultDao memoryToolResultDao;
    @Resource private CaseMemoryPublisher caseMemoryPublisher;
    @Resource(name = "mysqlJdbcTemplate") private JdbcTemplate jdbcTemplate;
    private final WorkflowTransitionPolicy transitionPolicy = new WorkflowTransitionPolicy();

    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@PathVariable("agentId") String agentId,
                                               @RequestBody ExplicitFeedbackRequest request) {
        request.validate();
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
        return Map.of("success", true, "id", feedback.getId(), "sourceType", "EXPLICIT");
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
        return Map.of("success", true, "id", feedback.getId(), "sourceType", feedback.getSourceType());
    }

    @GetMapping("/feedback")
    public List<AiFeedback> feedback(@PathVariable("agentId") String agentId,
                                     @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return feedbackDao.queryExplicitByAgentId(agentId, bounded(limit));
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
        int resolved = "RESOLVED".equals(toStatus) || "PROMOTED".equals(toStatus) || "INVALID".equals(toStatus) ? 1 : 0;
        int changed = feedbackDao.transitionStatus(feedbackId, agentId, item.getStatus(), toStatus,
                safe(request.category()), safe(request.matchedCaseId()), resolved);
        if (changed != 1) throw new IllegalStateException("Feedback changed concurrently; refresh and retry");
        return Map.of("success", true, "feedbackId", feedbackId, "fromStatus", item.getStatus(), "toStatus", toStatus);
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
        List<Map<String, Object>> evidence = jdbcTemplate.queryForList("SELECT * FROM case_evidence WHERE agent_id=? AND case_id=? ORDER BY id DESC LIMIT 100", agentId, caseId)
                .stream().map(row -> {
                    Map<String, Object> evidenceRow = new HashMap<>(row);
                    Object messageId = evidenceRow.get("message_id");
                    if (messageId instanceof Number number) evidenceRow.put("source", sourceOf(agentId, number.longValue()));
                    return evidenceRow;
                }).toList();
        return Map.of(
                "case", item,
                "evidence", evidence,
                "scoreSnapshots", jdbcTemplate.queryForList("SELECT * FROM case_score_snapshot WHERE agent_id=? AND case_id=? ORDER BY id DESC LIMIT 50", agentId, caseId),
                "reviews", jdbcTemplate.queryForList("SELECT * FROM case_review_record WHERE agent_id=? AND case_id=? ORDER BY id DESC LIMIT 50", agentId, caseId));
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
        int changed = caseDao.transitionStatus(agentId, caseId, item.getStatus(), toStatus,
                safe(request.owner()), safe(request.resolution()));
        if (changed != 1) throw new IllegalStateException("Case changed concurrently; refresh and retry");
        jdbcTemplate.update("INSERT INTO case_review_record(case_id,agent_id,from_status,to_status,actor,reason) VALUES (?,?,?,?,?,?)",
                caseId, agentId, item.getStatus(), toStatus, request.actor().trim(), safe(request.reason()));
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
        jdbcTemplate.update("INSERT INTO case_review_record(case_id,agent_id,from_status,to_status,actor,reason) VALUES (?,?,?,?,?,?)",
                caseId, agentId, source.getStatus(), "MERGED", request.actor().trim(), resolution);
        return Map.of("success", true, "caseId", caseId, "mergedToCaseId", targetCaseId);
    }

    @GetMapping("/workspace/stats")
    public Map<String, Object> stats(@PathVariable("agentId") String agentId) {
        long feedback = feedbackDao.countExplicitByAgentId(agentId);
        long negative = feedbackDao.countNegativeByAgentId(agentId);
        return Map.of(
                "agentId", agentId,
                "todayFeedback", feedbackDao.countExplicitTodayByAgentId(agentId),
                "explicitFeedback", feedback,
                "negativeFeedback", negative,
                "satisfactionRate", feedback == 0 ? 0 : Math.round((feedback - negative) * 10000d / feedback) / 100d,
                "totalCases", caseDao.countByAgent(agentId),
                "pendingCases", caseDao.countByAgentAndStatus(agentId, "PENDING_REVIEW"),
                "highPriorityCases", caseDao.countByAgentAndStatus(agentId, "IN_PROGRESS"),
                "resolvedCases", caseDao.countByAgentAndStatus(agentId, "RESOLVED"));
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
    private Map<String, Object> sourceOf(String agentId, Long messageId) {
        ChatMessage message = chatMessageDao.queryById(messageId);
        if (message == null || !agentId.equals(message.getAgentId())) throw new IllegalArgumentException("Message source does not belong to this Agent");
        String content = message.getContent() == null ? "" : message.getContent().replaceAll("\\s+", " ").trim();
        return Map.of("agentId", agentId, "sessionId", message.getSessionId(), "messageId", message.getId(), "role", message.getRole(), "preview", content.substring(0, Math.min(content.length(), 240)));
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public record CaseTransitionRequest(String toStatus, String actor, String reason, String owner, String resolution) {}
    public record CaseMergeRequest(String targetCaseId, String actor, String reason) {}
    public record FeedbackTransitionRequest(String toStatus, String actor, String reason, String category, String matchedCaseId) {}
}
