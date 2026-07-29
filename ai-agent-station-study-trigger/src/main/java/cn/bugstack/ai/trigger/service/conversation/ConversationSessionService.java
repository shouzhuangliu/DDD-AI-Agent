package cn.bugstack.ai.trigger.service.conversation;

import cn.bugstack.ai.infrastructure.dao.IAgentExecutionDao;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryToolResultDao;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentExecution;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTask;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationSessionService {

    @Resource private IAgentExecutionDao executionDao;
    @Resource private IAiAgentDao agentDao;
    @Resource private IAiCaseDao caseDao;
    @Resource private IAiFeedbackDao feedbackDao;
    @Resource private IAiSessionDao sessionDao;
    @Resource private IChatMessageDao messageDao;
    @Resource private IMemorySummaryDao summaryDao;
    @Resource private IMemoryStateDao stateDao;
    @Resource private IMemoryToolResultDao toolResultDao;
    @Resource private ISubagentTaskDao subagentTaskDao;

    public AiSession create(String agentId, String title, String modelId) {
        requireAgent(agentId);
        AiSession session = AiSession.builder()
                .sessionId(ConversationIdPolicy.create())
                .agentId(agentId)
                .title(safeTitle(title))
                .modelId(safe(modelId))
                .preview("")
                .messageCount(0)
                .status(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .lastMessageAt(LocalDateTime.now())
                .build();
        sessionDao.insert(session);
        return session;
    }

    public AiSession requireOwned(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        AiSession session = sessionDao.queryByAgentAndSession(agentId, sessionId);
        if (session == null) throw new IllegalArgumentException("Conversation does not belong to this Agent");
        return session;
    }

    public List<AiSession> list(String agentId, int limit) {
        requireAgent(agentId);
        return sessionDao.queryByAgentId(agentId, Math.max(1, Math.min(limit, 100))).stream()
                .filter(session -> session.getStatus() == null || session.getStatus() == 1)
                .toList();
    }

    public Map<String, Object> detail(String agentId, String sessionId) {
        AiSession session = requireOwned(agentId, sessionId);
        List<ChatMessage> messages = messageDao.queryBySessionId(sessionId).stream()
                .filter(message -> agentId.equals(message.getAgentId()))
                .toList();
        List<AiFeedback> feedbackRecords = feedbackDao.queryBySession(agentId, sessionId, 20);
        List<AiCase> caseRecords = caseDao.queryBySession(agentId, sessionId, 10);
        MemorySummary summary = summaryDao.queryLatest(sessionId);
        AgentExecution latestExecution = executionDao.queryLatestBySession(agentId, sessionId);
        List<Map<String, Object>> feedback = feedbackRecords.stream()
                .map(item -> feedbackView(item, messages))
                .toList();
        List<Map<String, Object>> cases = caseRecords.stream()
                .map(this::caseView)
                .toList();
        List<Map<String, Object>> subagents = latestExecution == null
                ? List.of()
                : subagentTaskDao.queryByExecutionId(latestExecution.getExecutionId(), 20).stream()
                .map(this::subagentView)
                .toList();

        LinkedHashMap<String, Object> memory = new LinkedHashMap<>();
        memory.put("summary", nullable(summary));
        memory.put("state", nullable(stateDao.queryLatest(sessionId)));
        memory.put("toolResults", toolResultDao.queryBySession(sessionId, 50));

        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("session", session);
        detail.put("messages", messages);
        detail.put("memory", memory);
        detail.put("feedback", feedback);
        detail.put("cases", cases);
        detail.put("subagents", subagents);
        detail.put("overview", overview(messages, feedbackRecords, caseRecords, summary, latestExecution));
        detail.put("timeline", timeline(session, messages, feedbackRecords, caseRecords, subagents, summary, latestExecution));
        return detail;
    }

    public AiSession rename(String agentId, String sessionId, String title) {
        AiSession session = requireOwned(agentId, sessionId);
        String sanitizedTitle = safeTitle(title);
        sessionDao.updateTitle(sessionId, sanitizedTitle);
        session.setTitle(sanitizedTitle);
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    public AiSession delete(String agentId, String sessionId) {
        AiSession session = requireOwned(agentId, sessionId);
        sessionDao.softDelete(sessionId);
        session.setStatus(0);
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private void requireAgent(String agentId) {
        if (agentId == null || agentId.isBlank() || agentDao.queryByAgentId(agentId) == null) {
            throw new IllegalArgumentException("Agent does not exist");
        }
    }

    private static String safeTitle(String value) {
        String title = safe(value);
        return title.isBlank() ? "新对话" : title.substring(0, Math.min(title.length(), 100));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static Object nullable(Object value) {
        return value == null ? Map.of() : value;
    }

    private static Map<String, Object> overview(List<ChatMessage> messages,
                                                List<AiFeedback> feedback,
                                                List<AiCase> cases,
                                                MemorySummary summary,
                                                AgentExecution latestExecution) {
        long userMessageCount = messages.stream().filter(item -> "user".equals(item.getRole())).count();
        long assistantMessageCount = messages.stream().filter(item -> "assistant".equals(item.getRole())).count();
        long toolMessageCount = messages.stream().filter(item -> "tool".equals(item.getRole())).count();
        long openFeedbackCount = feedback.stream()
                .filter(item -> !"RESOLVED".equals(item.getStatus()) && !"INVALID".equals(item.getStatus()))
                .count();
        long promotedFeedbackCount = feedback.stream()
                .filter(item -> "PROMOTED".equals(item.getStatus()))
                .count();
        long businessFeedbackCount = feedback.stream()
                .filter(item -> !"AI_INFERRED".equalsIgnoreCase(safe(item.getSourceType())))
                .count();
        long aiObservationCount = feedback.stream()
                .filter(item -> "AI_INFERRED".equalsIgnoreCase(safe(item.getSourceType())))
                .count();
        long readyForCaseFeedbackCount = feedback.stream()
                .filter(item -> "VALID".equalsIgnoreCase(safe(item.getStatus()))
                        || "CLUSTERED".equalsIgnoreCase(safe(item.getStatus())))
                .count();

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("messageCount", messages.size());
        result.put("userMessageCount", userMessageCount);
        result.put("assistantMessageCount", assistantMessageCount);
        result.put("toolMessageCount", toolMessageCount);
        result.put("feedbackCount", feedback.size());
        result.put("businessFeedbackCount", businessFeedbackCount);
        result.put("aiObservationCount", aiObservationCount);
        result.put("openFeedbackCount", openFeedbackCount);
        result.put("readyForCaseFeedbackCount", readyForCaseFeedbackCount);
        result.put("promotedFeedbackCount", promotedFeedbackCount);
        AiFeedback latestFeedback = feedback.isEmpty() ? null : feedback.getFirst();
        result.put("latestFeedbackStatus", latestFeedback == null ? "" : safe(latestFeedback.getStatus()));
        result.put("latestFeedbackSourceType", latestFeedback == null ? "" : safe(latestFeedback.getSourceType()));
        result.put("latestMatchedCaseId", latestFeedback == null ? "" : safe(latestFeedback.getMatchedCaseId()));
        result.put("caseCount", cases.size());
        AiCase latestCase = cases.isEmpty() ? null : cases.getFirst();
        result.put("latestCaseId", latestCase == null ? "" : safe(latestCase.getCaseId()));
        result.put("latestCaseStatus", latestCase == null ? "" : safe(latestCase.getStatus()));
        result.put("hasMemorySummary", summary != null && safe(summary.getSummary()).length() >= 20);
        result.put("latestRouteType", latestExecution == null ? "" : safe(latestExecution.getRouteType()));
        result.put("latestExecutionStatus", latestExecution == null ? "" : safe(latestExecution.getStatus()));
        result.put("latestModelId", latestExecution == null ? "" : safe(latestExecution.getModelId()));
        result.put("latestExecutionId", latestExecution == null ? "" : safe(latestExecution.getExecutionId()));
        result.put("latestExecutionAt", latestExecution == null ? null : latestExecution.getUpdatedAt());
        result.put("latestExecutionStep", latestExecution == null ? 0 : latestExecution.getCurrentStep());
        result.put("latestExecutionStateJson", latestExecution == null ? "" : safe(latestExecution.getStateJson()));
        return result;
    }

    private Map<String, Object> feedbackView(AiFeedback item, List<ChatMessage> messages) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("sessionId", safe(item.getSessionId()));
        view.put("agentId", safe(item.getAgentId()));
        view.put("assistantMessageId", item.getAssistantMessageId());
        view.put("feedbackType", safe(item.getFeedbackType()));
        view.put("rating", item.getRating());
        view.put("message", safe(item.getMessage()));
        view.put("correction", safe(item.getCorrection()));
        view.put("sourceType", safe(item.getSourceType()));
        view.put("sourceLabel", feedbackSourceLabel(item.getSourceType()));
        view.put("status", safe(item.getStatus()));
        view.put("statusLabel", feedbackStatusLabel(item.getStatus()));
        view.put("category", safe(item.getCategory()));
        view.put("matchedCaseId", safe(item.getMatchedCaseId()));
        view.put("resolved", item.getResolved());
        view.put("submittedBy", safe(item.getSubmittedBy()));
        view.put("createdAt", item.getCreatedAt());
        view.put("updatedAt", item.getUpdatedAt());
        view.put("qualificationHint", feedbackQualificationHint(item));
        view.put("sourcePreview", feedbackSourcePreview(item, messages));
        view.put("evaluationReason", feedbackEvaluationReason(item));
        view.put("nextAction", feedbackNextAction(item));
        return view;
    }

    private Map<String, Object> caseView(AiCase item) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("caseId", safe(item.getCaseId()));
        view.put("agentId", safe(item.getAgentId()));
        view.put("title", safe(item.getTitle()));
        view.put("summary", safe(item.getSummary()));
        view.put("status", safe(item.getStatus()));
        view.put("statusLabel", caseStatusLabel(item.getStatus()));
        view.put("severity", safe(item.getSeverity()));
        view.put("totalScore", item.getTotalScore());
        view.put("owner", safe(item.getOwner()));
        view.put("resolution", safe(item.getResolution()));
        view.put("updatedAt", item.getUpdatedAt());
        view.put("lastSeenAt", item.getLastSeenAt());
        return view;
    }

    private Map<String, Object> subagentView(SubagentTask item) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("taskId", safe(item.getTaskId()));
        view.put("executionId", safe(item.getExecutionId()));
        view.put("agentId", safe(item.getAgentId()));
        view.put("description", safe(item.getDescription()));
        view.put("status", safe(item.getStatus()));
        view.put("result", safe(item.getResult()));
        view.put("errorMessage", safe(item.getErrorMessage()));
        view.put("cancelRequested", item.getCancelRequested());
        view.put("startedAt", item.getStartedAt());
        view.put("completedAt", item.getCompletedAt());
        view.put("updatedAt", item.getUpdatedAt());
        return view;
    }

    private List<Map<String, Object>> timeline(AiSession session,
                                               List<ChatMessage> messages,
                                               List<AiFeedback> feedback,
                                               List<AiCase> cases,
                                               List<Map<String, Object>> subagents,
                                               MemorySummary summary,
                                               AgentExecution latestExecution) {
        java.util.ArrayList<Map<String, Object>> items = new java.util.ArrayList<>();
        if (session != null) {
            items.add(timelineItem(session.getCreatedAt(), "SESSION_CREATED", "会话创建",
                    safe(session.getTitle()).isBlank() ? safe(session.getSessionId()) : safe(session.getTitle()),
                    "INFO", safe(session.getSessionId())));
        }
        if (latestExecution != null) {
            items.add(timelineItem(latestExecution.getUpdatedAt(), "EXECUTION",
                    "最近一次执行",
                    "路由=" + safe(latestExecution.getRouteType()) + "，状态=" + safe(latestExecution.getStatus())
                            + "，步骤=" + (latestExecution.getCurrentStep() == null ? 0 : latestExecution.getCurrentStep()),
                    safe(latestExecution.getStatus()), safe(latestExecution.getExecutionId())));
        }
        if (summary != null && !safe(summary.getSummary()).isBlank()) {
            items.add(timelineItem(summary.getCreatedAt(), "MEMORY_SUMMARY", "短期记忆摘要",
                    safe(summary.getSummary()), safe(summary.getStatus()), safe(summary.getSessionId())));
        }
        for (AiFeedback item : feedback) {
            items.add(timelineItem(item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt(),
                    "FEEDBACK", "反馈评测",
                    feedbackSourceLabel(item.getSourceType()) + " · " + feedbackStatusLabel(item.getStatus())
                            + " · " + safe(item.getMessage()),
                    safe(item.getStatus()), String.valueOf(item.getId())));
        }
        for (AiCase item : cases) {
            items.add(timelineItem(item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt(),
                    "CASE", "Case流转",
                    caseStatusLabel(item.getStatus()) + " · " + safe(item.getTitle()),
                    safe(item.getStatus()), safe(item.getCaseId())));
        }
        for (Map<String, Object> item : subagents) {
            LocalDateTime time = (LocalDateTime) (item.get("updatedAt") != null ? item.get("updatedAt") : item.get("completedAt"));
            items.add(timelineItem(time,
                    "SUBAGENT", "子任务执行",
                    safe((String) item.get("description")) + " · " + safe((String) item.get("status")),
                    safe((String) item.get("status")), safe((String) item.get("taskId"))));
        }
        items.sort(Comparator.comparing(
                value -> (LocalDateTime) value.get("time"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(items);
    }

    private Map<String, Object> timelineItem(LocalDateTime time,
                                             String type,
                                             String title,
                                             String summary,
                                             String status,
                                             String refId) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("time", time);
        item.put("type", type);
        item.put("title", title);
        item.put("summary", summary);
        item.put("status", status);
        item.put("refId", refId);
        return item;
    }

    private String feedbackSourcePreview(AiFeedback item, List<ChatMessage> messages) {
        if (item.getAssistantMessageId() != null) {
            for (ChatMessage message : messages) {
                if (item.getAssistantMessageId().equals(message.getId())) {
                    return preview(message.getContent());
                }
            }
        }
        for (ChatMessage message : messages) {
            if ("user".equalsIgnoreCase(message.getRole())) {
                return preview(message.getContent());
            }
        }
        return "";
    }

    private String preview(String content) {
        String text = safe(content).replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120);
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

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase())) return true;
        }
        return false;
    }

    private String feedbackSourceLabel(String sourceType) {
        return switch (safe(sourceType).toUpperCase()) {
            case "EXPLICIT", "USER" -> "用户反馈";
            case "OPERATIONS" -> "运维反馈";
            case "TEST" -> "测试反馈";
            case "AI_INFERRED" -> "AI观察";
            default -> safe(sourceType);
        };
    }

    private String feedbackStatusLabel(String status) {
        return switch (safe(status).toUpperCase()) {
            case "OPEN" -> "新反馈";
            case "AI_EVALUATING" -> "AI评测中";
            case "NEED_MORE_INFO" -> "待补充信息";
            case "VALID" -> "已判定有效";
            case "CLUSTERED" -> "待升级Case";
            case "PROMOTED" -> "已升级Case";
            case "RESOLVED" -> "已关闭";
            case "INVALID" -> "无效反馈";
            default -> safe(status);
        };
    }

    private String caseStatusLabel(String status) {
        return switch (safe(status).toUpperCase()) {
            case "CANDIDATE" -> "候选Case";
            case "PENDING_REVIEW" -> "待审核";
            case "CONFIRMED" -> "已确认";
            case "IN_PROGRESS" -> "处理中";
            case "RESOLVED" -> "已解决";
            case "ARCHIVED" -> "已归档";
            case "IGNORED" -> "已驳回";
            case "MERGED" -> "已合并";
            default -> safe(status);
        };
    }
}
