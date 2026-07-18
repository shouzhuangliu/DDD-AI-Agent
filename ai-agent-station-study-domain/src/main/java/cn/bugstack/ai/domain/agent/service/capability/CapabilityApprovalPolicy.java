package cn.bugstack.ai.domain.agent.service.capability;

import java.util.List;

public class CapabilityApprovalPolicy {

    public void requireReviewerSeparation(String submittedBy, String reviewer, String reviewType) {
        if (submittedBy == null || reviewer == null || submittedBy.equalsIgnoreCase(reviewer)) {
            throw new IllegalStateException("Submitter cannot perform " + reviewType + " review");
        }
    }

    public void requireReleaseAllowed(String submittedBy, String releaseManager, List<Approval> approvals) {
        if (submittedBy == null || releaseManager == null || submittedBy.equalsIgnoreCase(releaseManager)) {
            throw new IllegalStateException("Submitter cannot release own version");
        }
        boolean testApproved = approvals.stream().anyMatch(a -> approved(a, "TEST"));
        boolean securityApproved = approvals.stream().anyMatch(a -> approved(a, "SECURITY"));
        if (!testApproved || !securityApproved) {
            throw new IllegalStateException("TEST and SECURITY approvals are required");
        }
    }

    private boolean approved(Approval approval, String type) {
        return type.equalsIgnoreCase(approval.type()) && "APPROVED".equalsIgnoreCase(approval.decision());
    }

    public record Approval(String type, String decision, String reviewer) {}
}
