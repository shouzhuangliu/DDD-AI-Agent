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
                "CONFIRMED", Set.of("PENDING_REVIEW", "IN_PROGRESS", "MERGED"),
                "IN_PROGRESS", Set.of("RESOLVED", "CONFIRMED"),
                "RESOLVED", Set.of("IN_PROGRESS", "ARCHIVED"),
                "ARCHIVED", Set.of("CONFIRMED"),
                "IGNORED", Set.of("CANDIDATE"),
                "MERGED", Set.of("CANDIDATE")
        ));
        transitions.put(Resource.FEEDBACK, Map.of(
                "OPEN", Set.of("AI_EVALUATING", "INVALID"),
                "AI_EVALUATING", Set.of("VALID", "INVALID", "NEED_MORE_INFO"),
                "NEED_MORE_INFO", Set.of("AI_EVALUATING", "INVALID"),
                "VALID", Set.of("CLUSTERED", "PROMOTED", "INVALID"),
                "CLUSTERED", Set.of("PROMOTED", "VALID", "INVALID"),
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
            throw new IllegalStateException(resourceLabel(resource) + "状态流转不允许：" + statusLabel(resource, from) + " -> " + statusLabel(resource, to));
        }
    }

    private String resourceLabel(Resource resource) {
        return switch (resource) {
            case CASE -> "Case";
            case FEEDBACK -> "反馈";
            case MCP -> "MCP";
            case SKILL -> "Skill";
        };
    }

    private String statusLabel(Resource resource, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (resource == Resource.FEEDBACK) {
            return switch (normalized) {
                case "OPEN" -> "新反馈";
                case "AI_EVALUATING" -> "AI评测中";
                case "NEED_MORE_INFO" -> "待补充信息";
                case "VALID" -> "待升级判断";
                case "CLUSTERED" -> "待升级Case";
                case "PROMOTED" -> "已升级为Case";
                case "INVALID" -> "无效反馈";
                case "RESOLVED" -> "已关闭";
                default -> normalized;
            };
        }
        return normalized;
    }
}
