package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.infrastructure.dao.IFeedbackEvaluationJobDao;
import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import org.junit.Test;

import java.time.LocalDateTime;

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

    @Test
    public void daoContractExposesWorkerLifecycleOperations() {
        CapturingDao dao = new CapturingDao();
        dao.claimable = FeedbackEvaluationJob.builder().id(7L).status("PENDING").build();

        FeedbackEvaluationJob claimable = dao.queryClaimable();
        int claimed = dao.claim(claimable.getId(), LocalDateTime.now().plusMinutes(2));
        int completed = dao.markComplete(claimable.getId());
        int failed = dao.markFailure(claimable.getId(), "RETRY", "temporary error");

        assertEquals(Long.valueOf(7L), claimable.getId());
        assertEquals(1, claimed);
        assertEquals(1, completed);
        assertEquals(1, failed);
    }

    private static class CapturingDao implements IFeedbackEvaluationJobDao {
        private FeedbackEvaluationJob captured;
        private FeedbackEvaluationJob claimable;

        @Override
        public int insertIgnore(FeedbackEvaluationJob job) {
            captured = job;
            return 1;
        }

        @Override
        public FeedbackEvaluationJob queryClaimable() {
            return claimable;
        }

        @Override
        public int claim(Long id, LocalDateTime leaseUntil) {
            return 1;
        }

        @Override
        public int markComplete(Long id) {
            return 1;
        }

        @Override
        public int markFailure(Long id, String status, String errorMessage) {
            return 1;
        }
    }
}
