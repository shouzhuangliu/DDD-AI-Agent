package cn.bugstack.ai.domain.agent.service.memory;

/**
 * 一次工具调用及其回执的完整交换记录。
 * 原始结果只允许从当前会话的持久化记录中恢复，不重新执行工具。
 */
public record ToolCallExchange(
        String sessionId,
        String toolCallId,
        String toolName,
        String toolArguments,
        String assistantContent,
        String resultContent) {
}
