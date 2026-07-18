package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.infrastructure.dao.IFeedbackEvaluationJobDao;
import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FeedbackEvaluationJobQueueTest {

    @Test
    public void enqueuesManualFeedbackWithStableIdempotencyKey() {
        CapturingDao dao = new CapturingDao();
        FeedbackEvaluationJobQueue queue = new FeedbackEvaluationJobQueue(dao);

        queue.enqueue("agent-1", 99L);

        assertEquals("feedback-evaluation:v1:99", dao.captured.getIdempotencyKey());
        assertEquals("agent-1", dao.captured.getAgentId());
        assertEquals(Long.valueOf(99L), dao.captured.getFeedbackId());
        assertEquals("PENDING", dao.captured.getStatus());
        assertEquals(Integer.valueOf(0), dao.captured.getAttempts());
        assertEquals(Integer.valueOf(3), dao.captured.getMaxAttempts());
    }

    private static class CapturingDao implements IFeedbackEvaluationJobDao {
        private FeedbackEvaluationJob captured;

        @Override
        public int insertIgnore(FeedbackEvaluationJob job) {
            captured = job;
            return 1;
        }
    }
}
