package cn.bugstack.ai.trigger.service.feedback;

import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class FeedbackAutoCaptureService {

    @Resource
    private IAiFeedbackDao feedbackDao;

    @Resource
    private FeedbackEvaluationJobQueue feedbackEvaluationJobQueue;

    public Long captureUserIssue(String agentId, String sessionId, String message) {
        if (agentId == null || agentId.isBlank() || message == null || message.isBlank()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        AiFeedback feedback = AiFeedback.builder()
                .sessionId(sessionId == null ? "" : sessionId)
                .agentId(agentId)
                .assistantMessageId(null)
                .feedbackType("ISSUE_REPORT")
                .rating(1)
                .message(message.trim())
                .correction("")
                .sourceType("USER")
                .category("业务问题反馈")
                .matchedCaseId("")
                .resolved(0)
                .status("OPEN")
                .submittedBy("chat-route")
                .createdAt(now)
                .updatedAt(now)
                .build();
        feedbackDao.insert(feedback);
        try {
            feedbackEvaluationJobQueue.enqueue(agentId, feedback.getId());
        } catch (Exception exception) {
            log.warn("自动反馈已保存，但入评测队列失败 feedbackId={}", feedback.getId(), exception);
        }
        return feedback.getId();
    }
}
