package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.IFeedbackEvaluationJobDao;
import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class FeedbackEvaluationJobQueue {

    public static final String POLICY_VERSION = "v1";

    @Resource
    private IFeedbackEvaluationJobDao jobDao;

    public FeedbackEvaluationJobQueue() {
    }

    public FeedbackEvaluationJobQueue(IFeedbackEvaluationJobDao jobDao) {
        this.jobDao = jobDao;
    }

    public void enqueue(String agentId, Long feedbackId) {
        if (feedbackId == null || feedbackId <= 0) return;
        LocalDateTime now = LocalDateTime.now();
        jobDao.insertIgnore(FeedbackEvaluationJob.builder()
                .idempotencyKey("feedback-evaluation:" + POLICY_VERSION + ":" + feedbackId)
                .agentId(agentId)
                .feedbackId(feedbackId)
                .policyVersion(POLICY_VERSION)
                .status("PENDING")
                .attempts(0)
                .maxAttempts(3)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
