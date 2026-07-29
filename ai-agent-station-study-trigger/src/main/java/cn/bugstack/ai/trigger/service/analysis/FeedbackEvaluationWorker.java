package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IFeedbackEvaluationJobDao;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FeedbackEvaluationWorker {

    private static final Pattern DOMAIN_TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z]{3,}|\\d{2,}");

    @Resource
    private IFeedbackEvaluationJobDao jobDao;

    @Resource
    private IAiFeedbackDao feedbackDao;

    @Resource
    private IAgentRepository agentRepository;

    @Resource
    private SkillScannerService skillScannerService;

    @Resource
    private AgentWorkspaceService agentWorkspaceService;

    @Value("${agent.feedback-evaluation.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${agent.feedback-evaluation.poll-delay-ms:5000}")
    public void processNext() {
        if (!enabled) return;
        FeedbackEvaluationJob job = jobDao.queryClaimable();
        if (job == null || jobDao.claim(job.getId(), LocalDateTime.now().plusMinutes(2)) != 1) return;
        try {
            AiFeedback feedback = feedbackDao.queryById(job.getFeedbackId());
            if (feedback == null) {
                jobDao.markComplete(job.getId());
                return;
            }
            if (!job.getAgentId().equals(feedback.getAgentId())) {
                jobDao.markFailure(job.getId(), "FAILED", "feedback agent mismatch");
                return;
            }
            if (!"OPEN".equals(feedback.getStatus())) {
                jobDao.markComplete(job.getId());
                return;
            }
            Evaluation evaluation = evaluate(job.getAgentId(), feedback);
            feedbackDao.transitionStatus(
                    feedback.getId(),
                    feedback.getAgentId(),
                    feedback.getStatus(),
                    evaluation.status(),
                    evaluation.category(),
                    "",
                    evaluation.resolved()
            );
            jobDao.markComplete(job.getId());
        } catch (Exception exception) {
            int nextAttempt = job.getAttempts() == null ? 1 : job.getAttempts() + 1;
            String state = nextAttempt >= job.getMaxAttempts() ? "FAILED" : "RETRY";
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            jobDao.markFailure(job.getId(), state, message.substring(0, Math.min(2000, message.length())));
            log.warn("Feedback evaluation failed, jobId={}, state={}", job.getId(), state, exception);
        }
    }

    private Evaluation evaluate(String agentId, AiFeedback feedback) {
        String text = feedback.getMessage() == null ? "" : feedback.getMessage().replaceAll("\\s+", " ").trim();
        if (text.isBlank() || text.length() < 6) {
            return new Evaluation("INVALID", "NON_ISSUE", 1);
        }

        if (containsAny(text, "谢谢", "收到", "好的", "明白", "辛苦", "hello", "hi")
                && !containsAny(text, "问题", "异常", "报错", "不一致", "缺货", "补货", "漏洞", "超时")) {
            return new Evaluation("INVALID", "NON_ISSUE", 1);
        }

        boolean hasProblem = containsAny(text,
                "问题", "异常", "错误", "失败", "报错", "不一致", "不对", "漏洞", "超时", "bug",
                "缺货", "空缺", "补货", "缺失", "找不到", "没货");
        boolean hasBusinessObject = containsAny(text,
                "订单", "库存", "商品", "缓存", "支付", "退款", "数据库", "db", "接口", "用户", "账号",
                "物流", "价格", "显卡", "业务", "内存", "型号", "sku", "页面");
        boolean hasEvidence = containsAny(text,
                "型号", "id", "ID", "sku", "订单号", "页面", "接口", "内存", "显卡", "品牌", "ddr", "截图", "日志")
                || text.matches(".*\\d{2,}.*");
        boolean matchesAgentDomain = matchesAgentBusinessContext(agentId, text);

        if (!hasProblem && !hasBusinessObject && !matchesAgentDomain) {
            return new Evaluation("INVALID", "NON_ISSUE", 1);
        }

        String category = categoryOf(text);
        if (hasProblem && (hasBusinessObject || matchesAgentDomain) && hasEvidence) {
            return new Evaluation("VALID", category, 0);
        }
        if (hasProblem || hasBusinessObject || matchesAgentDomain) {
            return new Evaluation("NEED_MORE_INFO", category, 0);
        }
        return new Evaluation("INVALID", "NON_ISSUE", 1);
    }

    private boolean matchesAgentBusinessContext(String agentId, String text) {
        if (agentId == null || agentId.isBlank() || text == null || text.isBlank()) return false;
        try {
            Set<String> keywords = collectAgentBusinessKeywords(agentId);
            if (keywords.isEmpty()) return false;
            String lower = text.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (keyword.length() >= 2 && lower.contains(keyword)) return true;
            }
            return false;
        } catch (Exception exception) {
            log.debug("匹配 Agent 业务上下文失败 agentId={}", agentId, exception);
            return false;
        }
    }

    private Set<String> collectAgentBusinessKeywords(String agentId) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        AiAgentVO agent = agentRepository.queryAgentById(agentId);
        if (agent != null) {
            appendTokens(keywords, agent.getAgentName());
            appendTokens(keywords, agent.getDescription());
            String workspace = agentWorkspaceService.resolveWorkDir(agentId, agent.getWorkDir(), System.getProperty("user.dir")).toString();
            for (String skillId : agentRepository.queryBoundSkillIds(agentId)) {
                SkillScannerService.SkillInfo metadata = skillScannerService.readSkillMetadataFromWorkDir(workspace, skillId);
                if (metadata == null) continue;
                appendTokens(keywords, metadata.getSkillId());
                appendTokens(keywords, metadata.getSkillName());
                appendTokens(keywords, metadata.getDescription());
            }
        }
        return keywords;
    }

    private void appendTokens(Set<String> keywords, String rawText) {
        if (rawText == null || rawText.isBlank()) return;
        Matcher matcher = DOMAIN_TOKEN.matcher(rawText.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 2) continue;
            if (containsAny(token, "skill", "agent", "demo", "issue", "report", "feedback")) continue;
            keywords.add(token);
        }
    }

    private String categoryOf(String text) {
        if (containsAny(text, "缺货", "补货", "空缺商品", "没货", "上架")) return "SUPPLY_GAP";
        if (containsAny(text, "缓存", "不一致", "对不上", "显示")) return "DATA_INCONSISTENCY";
        if (containsAny(text, "支付", "退款")) return "PAYMENT";
        if (containsAny(text, "超时", "卡", "很慢")) return "PERFORMANCE";
        return "ISSUE_REPORT";
    }

    private boolean containsAny(String text, String... words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private record Evaluation(String status, String category, int resolved) {
    }
}
