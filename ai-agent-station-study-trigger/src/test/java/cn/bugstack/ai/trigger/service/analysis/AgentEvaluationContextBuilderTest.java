package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationContextBuilderTest {

    @Test
    void buildsEvaluationContextWithAgentScopedLongTermMemoryRecall() {
        LongTermMemoryPort memoryPort = new LongTermMemoryPort() {
            @Override
            public void store(MemoryFact fact) {
            }

            @Override
            public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) {
                assertEquals("refund_agent", agentId);
                assertEquals("refund_agent", subjectId);
                assertTrue(query.contains("退款审批"));
                assertEquals(5, limit);
                return List.of(new MemoryFact(agentId, subjectId, "PUBLISHED_CASE",
                        "历史 Case：退款审批规则缺失会导致售后 Agent 给出不完整答案。",
                        "sess-old", "pgvector-bge-m3"));
            }
        };
        AgentEvaluationContextBuilder builder = new AgentEvaluationContextBuilder(memoryPort);

        String context = builder.build("refund_agent", List.of(
                message(1L, "user", "请解释退款审批流程"),
                message(2L, "assistant", "需要先确认订单状态"),
                message(3L, "user", "这里缺少退款审批规则，业务上不完整")
        ));

        assertTrue(context.contains("agentId=refund_agent"));
        assertTrue(context.contains("长期记忆召回"));
        assertTrue(context.contains("PUBLISHED_CASE"));
        assertTrue(context.contains("退款审批规则缺失"));
        assertTrue(context.contains("[3 user] 这里缺少退款审批规则"));
    }

    private ChatMessage message(Long id, String role, String content) {
        return ChatMessage.builder().id(id).role(role).content(content).build();
    }
}
