package cn.bugstack.ai.test.agent.capability;

import cn.bugstack.ai.domain.agent.service.capability.CapabilityApprovalPolicy;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class CapabilityApprovalPolicyTest {

    private final CapabilityApprovalPolicy policy = new CapabilityApprovalPolicy();

    @Test(expected = IllegalStateException.class)
    public void submitterCannotSecurityReviewOwnVersion() {
        policy.requireReviewerSeparation("alice", "alice", "SECURITY");
    }

    @Test(expected = IllegalStateException.class)
    public void releaseRequiresTestAndSecurityApprovals() {
        policy.requireReleaseAllowed("alice", "carol", List.of(
                new CapabilityApprovalPolicy.Approval("TEST", "APPROVED", "bob")));
    }

    @Test(expected = IllegalStateException.class)
    public void submitterCannotReleaseOwnVersion() {
        policy.requireReleaseAllowed("alice", "alice", List.of(
                new CapabilityApprovalPolicy.Approval("TEST", "APPROVED", "bob"),
                new CapabilityApprovalPolicy.Approval("SECURITY", "APPROVED", "carol")));
    }

    @Test
    public void distinctReleaseManagerCanReleaseApprovedVersion() {
        policy.requireReleaseAllowed("alice", "dave", List.of(
                new CapabilityApprovalPolicy.Approval("TEST", "APPROVED", "bob"),
                new CapabilityApprovalPolicy.Approval("SECURITY", "APPROVED", "carol")));
        assertTrue(true);
    }
}
