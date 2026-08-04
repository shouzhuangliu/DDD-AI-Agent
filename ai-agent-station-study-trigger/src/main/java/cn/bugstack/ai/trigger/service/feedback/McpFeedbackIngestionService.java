package cn.bugstack.ai.trigger.service.feedback;

import cn.bugstack.ai.infrastructure.dao.IAiCaseDao;
import cn.bugstack.ai.infrastructure.dao.ICaseEvidenceDao;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.CaseEvidence;
import cn.bugstack.ai.trigger.service.agent.AgentBusinessContextService;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将 MCP 的外部业务流水接入工作台数据库。
 *
 * MCP 进程只负责读取/写入自己的数据源，仪表盘只读 ai_feedback/ai_case；
 * 如果没有这一层，模型虽然能拿到结果，工作台却永远显示 0。该服务只接收
 * 成功的业务工具结果，运行时错误不会被误当成业务 Case。
 */
@Slf4j
@Service
public class McpFeedbackIngestionService {

    private static final String MCP_FEEDBACK_PREFIX = "[mcp-feedback-id:";

    @Resource
    private IAiFeedbackDao feedbackDao;
    @Resource
    private IAiCaseDao caseDao;
    @Resource
    private ICaseEvidenceDao caseEvidenceDao;
    @Resource
    private FeedbackEvaluationJobQueue feedbackEvaluationJobQueue;
    @Resource
    private AgentBusinessContextService agentBusinessContextService;

    /**
     * 由消息记录器在每次 MCP 工具返回后调用。toolName 可以是直接工具名，
     * 也可以是 call_mcp_tool；真正的 MCP 工具名从参数中的 toolName 解析。
     */
    public void ingest(String agentId, String sessionId, Long toolMessageId,
                       String toolName, String arguments, String content) {
        if (agentId == null || agentId.isBlank() || content == null || content.isBlank()) return;
        if (isRuntimeFailure(content)) return;
        String actualToolName = actualToolName(toolName, arguments);
        if (actualToolName.isBlank()) return;
        if ("get_today_feedback".equalsIgnoreCase(actualToolName)
                || "search_feedback_by_keyword".equalsIgnoreCase(actualToolName)) {
            importFeedbackRows(agentId, sessionId, toolMessageId, actualToolName, content);
            return;
        }
        if ("mark_feedback_triaged".equalsIgnoreCase(actualToolName)) {
            importTriage(agentId, sessionId, toolMessageId, toolName, arguments, content);
        }
    }

    private void importFeedbackRows(String agentId, String sessionId, Long toolMessageId,
                                    String toolName, String content) {
        String skillId = safe(agentBusinessContextService.boundBusinessSkillId(agentId));
        if (skillId.isBlank()) {
            log.info("MCP 反馈未入库：Agent 未绑定可解析的业务 Skill agentId={}", agentId);
            return;
        }
        List<JSONObject> rows = parseRows(content);
        for (JSONObject row : rows) {
            String externalRef = first(row, "feedbackId", "feedback_id", "id");
            String message = first(row, "content", "summary", "message");
            if (externalRef.isBlank() || message.isBlank()) continue;
            try {
                AiFeedback existing = feedbackDao.queryByAgentAndExternalRef(agentId, externalRef);
                if (existing != null) continue;
                LocalDateTime occurredAt = parseTime(first(row, "occurredAt", "occurred_at"));
                AiFeedback feedback = AiFeedback.builder()
                        .sessionId(safe(sessionId))
                        .agentId(agentId)
                        .assistantMessageId(null)
                        .feedbackType("ISSUE_REPORT")
                        .rating(1)
                        .message(message.trim())
                        .correction(externalRefMetadata(externalRef, row))
                        .sourceType(sourceType(first(row, "source")))
                        .category(category(first(row, "service")))
                        .matchedCaseId("")
                        .resolved(0)
                        .status("OPEN")
                        .submittedBy("mcp:" + safe(toolName))
                        .createdAt(occurredAt == null ? LocalDateTime.now() : occurredAt)
                        .updatedAt(LocalDateTime.now())
                        .build();
                feedbackDao.insert(feedback);
                if (feedback.getId() != null) {
                    feedbackEvaluationJobQueue.enqueue(agentId, feedback.getId());
                }
            } catch (Exception exception) {
                // 单条脏数据不能阻断同一批次剩余反馈的入库。
                log.warn("MCP 反馈入库失败 agentId={}, externalRef={}", agentId, externalRef, exception);
            }
        }
    }

    private void importTriage(String agentId, String sessionId, Long toolMessageId,
                               String toolName, String arguments, String content) {
        JSONObject triage = parseObject(content);
        if (triage == null) return;
        String externalRef = first(triage, "feedbackId", "feedback_id");
        if (externalRef.isBlank()) return;
        AiFeedback feedback = feedbackDao.queryByAgentAndExternalRef(agentId, externalRef);
        // 允许 MCP 在分诊结果中回传完整 Feedback，支持“只调用分诊工具”的场景。
        if (feedback == null) {
            JSONObject nested = triage.getJSONObject("feedback");
            if (nested != null) {
                importFeedbackRows(agentId, sessionId, toolMessageId, toolName,
                        JSON.toJSONString(List.of(nested)));
                feedback = feedbackDao.queryByAgentAndExternalRef(agentId, externalRef);
            }
        }
        if (feedback == null) return;

        String decision = first(triage, "decision", "triageDecision").toUpperCase(Locale.ROOT);
        if (decision.contains("PROMOTE_CASE") || decision.contains("PROMOTE_CANDIDATE")) {
            String caseId = "case-mcp-" + externalRef;
            feedbackDao.transitionStatus(feedback.getId(), agentId, safe(feedback.getStatus()),
                    "CLUSTERED", "MCP分诊升级", caseId, 0);
            createCandidateCase(agentId, sessionId, toolMessageId, feedback, caseId,
                    first(triage, "triageId", "triage_id"), first(triage, "note"));
        } else if (decision.contains("NEED_MORE_INFO")) {
            feedbackDao.transitionStatus(feedback.getId(), agentId, safe(feedback.getStatus()),
                    "NEED_MORE_INFO", "MCP分诊待补充", "", 0);
        } else if (decision.contains("IGNORE")) {
            feedbackDao.transitionStatus(feedback.getId(), agentId, safe(feedback.getStatus()),
                    "INVALID", "MCP分诊忽略", "", 1);
        }
    }

    private void createCandidateCase(String agentId, String sessionId, Long toolMessageId,
                                     AiFeedback feedback, String caseId, String triageId, String note) {
        String skillId = safe(agentBusinessContextService.boundBusinessSkillId(agentId));
        if (skillId.isBlank()) return;
        if (caseDao.queryByAgentAndCaseId(agentId, caseId) != null) return;
        String severity = severityFrom(safe(feedback.getCorrection()) + " " + feedback.getMessage());
        LocalDateTime now = LocalDateTime.now();
        String title = truncate(feedback.getMessage(), 200);
        AiCase candidate = AiCase.builder()
                .caseId(caseId)
                .agentId(agentId)
                .title(title)
                .summary(note.isBlank() ? feedback.getMessage() : note)
                .caseType("bug")
                .severity(severity)
                .frequency(1)
                .affectedSessions(1)
                .importanceScore(scoreFor(severity))
                .confidence(82d)
                .totalScore(scoreFor(severity))
                .status("CANDIDATE")
                .skillId(skillId)
                .sourceModel("mcp-triage")
                .extractionReason("业务 MCP 分诊" + (triageId.isBlank() ? "" : "，分诊编号=" + triageId))
                .owner("")
                .resolution("")
                .mergedToCaseId("")
                .lastSeenAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        caseDao.insert(candidate);
        caseEvidenceDao.insertIgnore(CaseEvidence.builder()
                .caseId(caseId)
                .agentId(agentId)
                .evidenceType("FEEDBACK")
                .evidenceId(feedback.getId())
                .sessionId(safe(sessionId))
                .messageId(toolMessageId)
                .excerpt(truncate(feedback.getMessage(), 1000))
                .evidenceRole("PRIMARY")
                .skillRuleId(skillId)
                .supportsJson("{\"source\":\"mcp-triage\"}")
                .createdAt(now)
                .build());
    }

    private List<JSONObject> parseRows(String content) {
        try {
            Object parsed = JSON.parse(content.trim());
            if (parsed instanceof JSONArray array) {
                List<JSONObject> rows = new ArrayList<>();
                for (Object value : array) if (value instanceof JSONObject object) rows.add(object);
                return rows;
            }
            if (parsed instanceof JSONObject object) {
                JSONArray items = object.getJSONArray("items");
                if (items == null) items = object.getJSONArray("feedback");
                if (items != null) {
                    List<JSONObject> rows = new ArrayList<>();
                    for (Object value : items) if (value instanceof JSONObject item) rows.add(item);
                    return rows;
                }
                return List.of(object);
            }
        } catch (Exception exception) {
            log.debug("MCP 反馈结果不是业务 JSON，跳过入库: {}", exception.getMessage());
        }
        return List.of();
    }

    private JSONObject parseObject(String content) {
        try {
            Object parsed = JSON.parse(content.trim());
            return parsed instanceof JSONObject object ? object : null;
        } catch (Exception exception) {
            log.debug("MCP 分诊结果不是 JSON，跳过同步: {}", exception.getMessage());
            return null;
        }
    }

    private String actualToolName(String toolName, String arguments) {
        String direct = safe(toolName);
        if (direct.equalsIgnoreCase("get_today_feedback")
                || direct.equalsIgnoreCase("search_feedback_by_keyword")
                || direct.equalsIgnoreCase("mark_feedback_triaged")) return direct;
        try {
            JSONObject object = JSON.parseObject(arguments == null ? "{}" : arguments);
            String nested = first(object, "toolName", "tool_name");
            if (!nested.isBlank()) return nested;
        } catch (Exception ignored) {
            // 非 JSON 参数会在工具层被拒绝，这里不产生业务记录。
        }
        return "";
    }

    private boolean isRuntimeFailure(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        return text.contains("mcp 调用异常") || text.contains("mcp调用异常")
                || text.contains("工具执行失败") || text.contains("未知工具")
                || text.contains("未授权工具") || text.contains("返回空内容")
                || text.startsWith("[") && text.contains("tool_");
    }

    private String externalRefMetadata(String externalRef, JSONObject row) {
        String service = first(row, "service");
        String severity = first(row, "severityHint", "severity");
        return truncate(MCP_FEEDBACK_PREFIX + externalRef + "] source=mcp"
                + (service.isBlank() ? "" : ", service=" + service)
                + (severity.isBlank() ? "" : ", severity=" + severity), 1000);
    }

    private String sourceType(String source) {
        return switch (safe(source).toUpperCase(Locale.ROOT)) {
            case "OPS", "MONITOR", "OPERATIONS" -> "OPERATIONS";
            case "TEST" -> "TEST";
            default -> "USER";
        };
    }

    private String category(String service) {
        String value = safe(service);
        return value.isBlank() ? "库存业务" : truncate(value, 32);
    }

    private LocalDateTime parseTime(String value) {
        if (value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
        catch (Exception ignored) { return null; }
    }

    private String severityFrom(String message) {
        String text = safe(message).toUpperCase(Locale.ROOT);
        if (text.contains("P0") || text.contains("锁库存") || text.contains("积压")) return "P0";
        if (text.contains("P1") || text.contains("超卖") || text.contains("不一致")) return "P1";
        return "P2";
    }

    private double scoreFor(String severity) {
        return switch (severity) { case "P0" -> 95d; case "P1" -> 82d; case "P2" -> 65d; default -> 40d; };
    }

    private String first(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object value = object.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return "";
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private String truncate(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
