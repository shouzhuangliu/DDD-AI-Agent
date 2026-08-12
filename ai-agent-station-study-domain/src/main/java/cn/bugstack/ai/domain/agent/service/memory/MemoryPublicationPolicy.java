package cn.bugstack.ai.domain.agent.service.memory;

import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Service
public class MemoryPublicationPolicy {

    private static final Set<String> PUBLISHABLE_TYPES = Set.of(
            "BUSINESS_RULE", "RESOLVED_CASE", "OPERATING_PLAYBOOK", "CAPABILITY_BOUNDARY");
    private static final Set<String> RUNTIME_ONLY_SOURCES = Set.of(
            "MCP_FAILURE", "TOOL_FAILURE", "MODEL_FAILURE", "MODEL_RATE_LIMIT", "EXECUTION_FAILURE");

    public enum CandidateStatus { EXTRACTED, PENDING_REVIEW, APPROVED, REJECTED, PUBLISHED }

    public void requireTransition(CandidateStatus from, CandidateStatus to) {
        boolean allowed = switch (from) {
            case EXTRACTED -> to == CandidateStatus.PENDING_REVIEW;
            case PENDING_REVIEW -> EnumSet.of(CandidateStatus.APPROVED, CandidateStatus.REJECTED).contains(to);
            case APPROVED -> to == CandidateStatus.PUBLISHED;
            default -> false;
        };
        if (!allowed) throw new IllegalStateException("非法长期记忆状态迁移: " + from + " -> " + to);
    }

    public void requireCandidateType(String memoryType, String sourceType) {
        String normalizedType = normalize(memoryType);
        String normalizedSource = normalize(sourceType);
        if (!PUBLISHABLE_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("不支持的长期记忆类型: " + memoryType);
        }
        if (RUNTIME_ONLY_SOURCES.contains(normalizedSource)) {
            throw new IllegalArgumentException("运行时异常不能成为长期记忆");
        }
    }

    public void requireResolvedCase(String sourceType, String caseStatus) {
        if ("CASE".equals(normalize(sourceType)) && !"RESOLVED".equals(normalize(caseStatus))) {
            throw new IllegalStateException("只有已解决 Case 才能发布为长期记忆");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
