package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiLlmLogDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.trigger.service.observability.ConversationTraceService;
import cn.bugstack.ai.trigger.service.observability.LlmLogObservationAssembler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@CrossOrigin("*")
public class DashboardController {

    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IAiLlmLogDao llmLogDao;
    @Resource private IChatMessageDao chatMessageDao;
    @Resource private ConversationTraceService conversationTraceService;
    private final LlmLogObservationAssembler llmLogObservationAssembler = new LlmLogObservationAssembler();

    @GetMapping("/dashboard/stats")
    public Map<String, Object> stats() {
        Map<String, Object> s = new HashMap<>();
        s.put("todayFeedback", feedbackDao.countToday());
        s.put("totalResolved", feedbackDao.countByResolved(1));
        s.put("totalUnresolved", feedbackDao.countByResolved(0));
        s.put("totalCases", caseDao.countAll());
        s.put("activeCases", caseDao.countByStatus("active"));
        s.put("archivedCases", caseDao.countByStatus("archived"));
        s.put("totalLlmLogs", llmLogDao.countAll());
        s.put("errorLogs", llmLogDao.countByStatus("error"));
        return s;
    }

    @GetMapping("/dashboard/top-cases")
    public List<AiCase> topCases(@RequestParam(value = "limit", defaultValue = "5") int limit) {
        return caseDao.queryTop(limit);
    }

    @GetMapping("/feedback/recent")
    public List<AiFeedback> recentFeedback(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return feedbackDao.queryRecent(limit);
    }

    @PostMapping("/feedback/legacy-auto-capture")
    public Map<String, Object> submitFeedback(@RequestBody AiFeedback fb) {
        fb.setCreatedAt(LocalDateTime.now());
        if (fb.getResolved() == null) fb.setResolved(0);
        feedbackDao.insert(fb);
        log.info("📝 反馈记录: agent={}, msg={}", fb.getAgentId(), fb.getMessage());
        return Map.of("success", true, "id", fb.getId());
    }

    @GetMapping("/cases/top")
    public List<AiCase> topCases() { return caseDao.queryTop(10); }

    @PutMapping("/cases/{caseId}/increment")
    public Map<String, Object> incrementCase(@PathVariable("caseId") String caseId) {
        caseDao.incrementFrequency(caseId);
        return Map.of("success", true);
    }

    @GetMapping("/llm-logs")
    public List<AiLlmLog> listLlmLogs(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return llmLogDao.queryRecent(limit);
    }

    @GetMapping("/llm-logs/grouped")
    public List<LlmLogObservationAssembler.AgentGroup> groupedLlmLogs(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return llmLogObservationAssembler.group(llmLogDao.queryRecent(bounded(limit)), chatMessageDao::queryBySessionId);
    }

    @GetMapping("/llm-logs/{id}")
    public AiLlmLog getLlmLog(@PathVariable("id") Long id) {
        return llmLogDao.queryById(id);
    }

    @GetMapping("/llm-logs/agent/{agentId}")
    public List<AiLlmLog> listByAgent(@PathVariable("agentId") String agentId,
                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return llmLogDao.queryByAgentId(agentId, limit);
    }

    @GetMapping("/llm-logs/session/{sessionId}")
    public List<AiLlmLog> listBySession(@PathVariable("sessionId") String sessionId,
                                         @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return llmLogDao.queryBySessionId(sessionId, limit);
    }

    @GetMapping("/agents/{agentId}/sessions/{sessionId}/trace")
    public ConversationTraceService.ConversationTrace conversationTrace(@PathVariable("agentId") String agentId,
                                                                        @PathVariable("sessionId") String sessionId) {
        return conversationTraceService.trace(agentId, sessionId);
    }

    private int bounded(int limit) {
        return Math.max(1, Math.min(500, limit));
    }
}
