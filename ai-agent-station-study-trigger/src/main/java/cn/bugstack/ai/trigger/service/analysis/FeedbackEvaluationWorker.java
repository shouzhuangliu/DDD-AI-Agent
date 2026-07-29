package cn.bugstack.ai.trigger.service.analysis;

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
import java.util.Locale;

@Slf4j
@Component
public class FeedbackEvaluationWorker {

    @Resource
    private IFeedbackEvaluationJobDao jobDao;

    @Resource
    private IAiFeedbackDao feedbackDao;

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
            Evaluation evaluation = evaluate(feedback);
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

    private Evaluation evaluate(AiFeedback feedback) {
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

        if (!hasProblem && !hasBusinessObject) {
            return new Evaluation("INVALID", "NON_ISSUE", 1);
        }

        String category = categoryOf(text);
        if (hasProblem && hasBusinessObject && hasEvidence) {
            return new Evaluation("VALID", category, 0);
        }
        if (hasProblem || hasBusinessObject) {
            return new Evaluation("NEED_MORE_INFO", category, 0);
        }
        return new Evaluation("INVALID", "NON_ISSUE", 1);
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
