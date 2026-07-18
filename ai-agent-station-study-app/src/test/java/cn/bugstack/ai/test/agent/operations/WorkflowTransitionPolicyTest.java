package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.service.operations.WorkflowTransitionPolicy;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkflowTransitionPolicyTest {

    private final WorkflowTransitionPolicy policy = new WorkflowTransitionPolicy();

    @Test
    public void allowsCaseReviewLifecycle() {
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "CANDIDATE", "PENDING_REVIEW"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "PENDING_REVIEW", "CONFIRMED"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "RESOLVED", "ARCHIVED"));
    }

    @Test
    public void rejectsSkippingCaseReview() {
        assertFalse(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "CANDIDATE", "RELEASED"));
    }

    @Test
    public void allowsCaseMergeFromReviewStates() {
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "CANDIDATE", "MERGED"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "PENDING_REVIEW", "MERGED"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.CASE, "CONFIRMED", "MERGED"));
    }

    @Test(expected = IllegalStateException.class)
    public void requireAllowedExplainsInvalidTransition() {
        policy.requireAllowed(WorkflowTransitionPolicy.Resource.MCP, "DRAFT", "RELEASED");
    }

    @Test
    public void allowsGovernedCapabilityLifecycle() {
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.MCP, "APPROVED", "RELEASED"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.SKILL, "APPROVED", "SIGNED"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.SKILL, "SIGNED", "RELEASED"));
    }

    @Test
    public void allowsFeedbackEvaluationLifecycle() {
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.FEEDBACK, "OPEN", "AI_EVALUATING"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.FEEDBACK, "AI_EVALUATING", "VALID"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.FEEDBACK, "VALID", "CLUSTERED"));
        assertTrue(policy.isAllowed(WorkflowTransitionPolicy.Resource.FEEDBACK, "CLUSTERED", "PROMOTED"));
    }

    @Test
    public void rejectsSkippingFeedbackEvaluation() {
        assertFalse(policy.isAllowed(WorkflowTransitionPolicy.Resource.FEEDBACK, "OPEN", "PROMOTED"));
    }
}
