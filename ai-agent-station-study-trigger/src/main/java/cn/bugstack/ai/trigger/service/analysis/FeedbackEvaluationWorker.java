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
            feedbackDao.transitionStatus(feedback.getId(), feedback.getAgentId(), feedback.getStatus(),
                    evaluation.status(), evaluation.category(), "", evaluation.resolved());
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
        if (text.length() < 12) {
            return new Evaluation("NEED_MORE_INFO", "NEED_INFO", 0);
        }
        boolean hasProblem = containsAny(text, "问题", "异常", "错误", "失败", "报错", "不一致", "不对", "漏洞", "超时", "bug");
        boolean hasBusinessObject = containsAny(text, "订单", "商品", "库存", "缓存", "支付", "退款", "数据库", "db", "接口", "用户", "账号", "物流", "价格", "显卡", "业务", "cs");
        if (!hasProblem) {
            return new Evaluation("NEED_MORE_INFO", "NEED_INFO", 0);
        }
        if (!hasBusinessObject) {
            return new Evaluation("NEED_MORE_INFO", "NEED_INFO", 0);
        }
        return new Evaluation("AI_EVALUATING", "AI_EVAL", 0);
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
