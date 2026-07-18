package cn.bugstack.ai.domain.agent.service.operations;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Central lifecycle guard shared by Case, MCP and Skill workflows. */
public class WorkflowTransitionPolicy {

    public enum Resource { CASE, FEEDBACK, MCP, SKILL }

    private final Map<Resource, Map<String, Set<String>>> transitions = new EnumMap<>(Resource.class);

    public WorkflowTransitionPolicy() {
        transitions.put(Resource.CASE, Map.of(
                "CANDIDATE", Set.of("PENDING_REVIEW", "IGNORED", "MERGED"),
                "PENDING_REVIEW", Set.of("CONFIRMED", "IGNORED", "CANDIDATE", "MERGED"),
                "CONFIRMED", Set.of("IN_PROGRESS", "ARCHIVED", "MERGED"),
                "IN_PROGRESS", Set.of("RESOLVED", "CONFIRMED"),
                "RESOLVED", Set.of("IN_PROGRESS", "ARCHIVED"),
                "ARCHIVED", Set.of("CONFIRMED"),
                "IGNORED", Set.of("CANDIDATE"),
                "MERGED", Set.of("CANDIDATE")
        ));
        transitions.put(Resource.FEEDBACK, Map.of(
                "OPEN", Set.of("AI_EVALUATING", "PROMOTED", "INVALID", "NEED_MORE_INFO"),
                "AI_EVALUATING", Set.of("VALID", "PROMOTED", "INVALID", "NEED_MORE_INFO"),
                "NEED_MORE_INFO", Set.of("AI_EVALUATING", "PROMOTED", "INVALID"),
                "VALID", Set.of("CLUSTERED", "PROMOTED", "RESOLVED"),
                "CLUSTERED", Set.of("PROMOTED", "RESOLVED"),
                "PROMOTED", Set.of("RESOLVED"),
                "INVALID", Set.of("OPEN"),
                "RESOLVED", Set.of("OPEN")
        ));
        transitions.put(Resource.MCP, Map.of(
                "DRAFT", Set.of("CONNECTIVITY_CHECKED", "WITHDRAWN"),
                "CONNECTIVITY_CHECKED", Set.of("DISCOVERED", "DRAFT"),
                "DISCOVERED", Set.of("SCANNED", "DRAFT"),
                "SCANNED", Set.of("TESTED", "DRAFT"),
                "TESTED", Set.of("IN_REVIEW", "DRAFT"),
                "IN_REVIEW", Set.of("APPROVED", "DRAFT"),
                "APPROVED", Set.of("RELEASED", "DRAFT"),
                "RELEASED", Set.of("DEPRECATED", "WITHDRAWN"),
                "DEPRECATED", Set.of("RELEASED", "WITHDRAWN"),
                "WITHDRAWN", Set.of()
        ));
        transitions.put(Resource.SKILL, Map.ofEntries(
                Map.entry("UPLOADED", Set.of("QUARANTINED", "WITHDRAWN")),
                Map.entry("QUARANTINED", Set.of("VALIDATED", "WITHDRAWN")),
                Map.entry("VALIDATED", Set.of("SCANNED", "QUARANTINED")),
                Map.entry("SCANNED", Set.of("TESTED", "QUARANTINED")),
                Map.entry("TESTED", Set.of("IN_REVIEW", "QUARANTINED")),
                Map.entry("IN_REVIEW", Set.of("APPROVED", "QUARANTINED")),
                Map.entry("APPROVED", Set.of("SIGNED", "QUARANTINED")),
                Map.entry("SIGNED", Set.of("RELEASED", "QUARANTINED")),
                Map.entry("RELEASED", Set.of("DEPRECATED", "WITHDRAWN")),
                Map.entry("DEPRECATED", Set.of("RELEASED", "WITHDRAWN")),
                Map.entry("WITHDRAWN", Set.of())
        ));
    }

    public boolean isAllowed(Resource resource, String from, String to) {
        if (resource == null || from == null || to == null) {
            return false;
        }
        return transitions.getOrDefault(resource, Map.of())
                .getOrDefault(from.trim().toUpperCase(), Set.of())
                .contains(to.trim().toUpperCase());
    }

    public void requireAllowed(Resource resource, String from, String to) {
        if (!isAllowed(resource, from, to)) {
            throw new IllegalStateException("Invalid " + resource + " transition: " + from + " -> " + to);
        }
    }
}
