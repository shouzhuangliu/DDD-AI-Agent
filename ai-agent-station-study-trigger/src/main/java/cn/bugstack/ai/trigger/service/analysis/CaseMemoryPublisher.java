package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CaseMemoryPublisher {

    private static final Set<String> MEMORY_STATUSES = Set.of("CONFIRMED", "RESOLVED");

    private final LongTermMemoryPort longTermMemoryPort;

    public CaseMemoryPublisher(LongTermMemoryPort longTermMemoryPort) {
        this.longTermMemoryPort = longTermMemoryPort;
    }

    public void publish(AiCase item, String toStatus, String reason) {
        if (item == null || toStatus == null) return;
        String normalizedStatus = toStatus.trim().toUpperCase();
        if (!MEMORY_STATUSES.contains(normalizedStatus)) return;
        String kind = "RESOLVED".equals(normalizedStatus) ? "RESOLVED_CASE" : "PUBLISHED_CASE";
        longTermMemoryPort.store(new LongTermMemoryPort.MemoryFact(
                blank(item.getAgentId()),
                blank(item.getAgentId()),
                kind,
                content(item, normalizedStatus, reason),
                "",
                "case-review-" + normalizedStatus.toLowerCase()));
    }

    private static String content(AiCase item, String status, String reason) {
        return """
                Case 状态：%s
                Case ID：%s
                标题：%s
                类型：%s
                严重度：%s
                摘要：%s
                AI 归因：%s
                人工审核/处理说明：%s
                解决方案：%s
                """.formatted(status, blank(item.getCaseId()), blank(item.getTitle()), blank(item.getCaseType()),
                blank(item.getSeverity()), blank(item.getSummary()), blank(item.getExtractionReason()),
                blank(reason), blank(item.getResolution()));
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
