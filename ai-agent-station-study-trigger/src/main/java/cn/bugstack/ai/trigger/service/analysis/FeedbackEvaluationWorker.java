package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IFeedbackEvaluationJobDao;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import cn.bugstack.ai.trigger.service.agent.AgentBusinessContextService;
import cn.bugstack.ai.trigger.service.feedback.FeedbackAdmissionPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class FeedbackEvaluationWorker {

    @Resource
    private IFeedbackEvaluationJobDao jobDao;

    @Resource
    private IAiFeedbackDao feedbackDao;

    @Resource
    private FeedbackAdmissionPolicy feedbackAdmissionPolicy;

    @Resource
    private AgentBusinessContextService agentBusinessContextService;

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

        FeedbackAdmissionPolicy.FeedbackSignal signal = feedbackAdmissionPolicy.analyze(
                text, agentBusinessContextService.collectKeywords(agentId));

        if (signal.noise()) {
            return new Evaluation("INVALID", "NON_ISSUE", 1);
        }
        if (!signal.hasProblem() && !signal.hasBusinessObject() && !signal.matchesAgentBusiness()) {
            return new Evaluation("INVALID", "NON_ISSUE", 1);
        }

        String category = feedbackAdmissionPolicy.categoryOf(text);
        if (signal.concreteEnough()) {
            return new Evaluation("VALID", category, 0);
        }
        if (signal.likelyBusinessIssue() || signal.hasEvidence()) {
            return new Evaluation("NEED_MORE_INFO", category, 0);
        }
        return new Evaluation("INVALID", "NON_ISSUE", 1);
    }

    private record Evaluation(String status, String category, int resolved) {
    }
}
