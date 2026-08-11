package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 会话摘要的覆盖游标规则：摘要是旧消息的替代内容，只有被 endMessageId 覆盖的消息
 * 才能从下一次模型上下文中退出，数据库原文仍保留用于审计和取回。
 */
public record SummaryCoveragePolicy(long endMessageId) {

    public static SummaryCoveragePolicy of(Long endMessageId) {
        return new SummaryCoveragePolicy(endMessageId == null ? 0L : endMessageId);
    }

    public boolean hasCoverage() {
        return endMessageId > 0;
    }

    public boolean covers(Long messageId) {
        return hasCoverage() && messageId != null && messageId > 0 && messageId <= endMessageId;
    }
}
