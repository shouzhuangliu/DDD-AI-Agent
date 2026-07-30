package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.trigger.service.memory.MemoryQueryAdmissionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                assertTrue(query.contains("退款审核"));
                assertEquals(5, limit);
                return List.of(new MemoryFact(agentId, subjectId, "PUBLISHED_CASE",
                        "历史 Case：退款审核规则缺失会导致售后 Agent 给出不完整答案。",
                        "sess-old", "pgvector-bge-m3"));
            }
        };
        AgentEvaluationContextBuilder builder = new AgentEvaluationContextBuilder(
                memoryPort, new MemoryQueryAdmissionPolicy());

        String context = builder.build("refund_agent", List.of(
                message(1L, "user", "请解释退款审核流程"),
                message(2L, "assistant", "需要先确认订单状态"),
                message(3L, "user", "这里缺少退款审核规则，业务上不完整")
        ));

        assertTrue(context.contains("agentId=refund_agent"));
        assertTrue(context.contains("长期记忆召回"));
        assertTrue(context.contains("PUBLISHED_CASE"));
        assertTrue(context.contains("退款审核规则缺失"));
        assertTrue(context.contains("[3 user] 这里缺少退款审核规则，业务上不完整"));
    }

    @Test
    void skipsLongTermRecallForTrivialLatestUserInput() {
        AtomicBoolean recalled = new AtomicBoolean(false);
        LongTermMemoryPort memoryPort = new LongTermMemoryPort() {
            @Override
            public void store(MemoryFact fact) {
            }

            @Override
            public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) {
                recalled.set(true);
                return List.of();
            }
        };
        AgentEvaluationContextBuilder builder = new AgentEvaluationContextBuilder(
                memoryPort, new MemoryQueryAdmissionPolicy());

        String context = builder.build("cs", List.of(
                message(1L, "assistant", "请补充你的问题"),
                message(2L, "user", "1")
        ));

        assertFalse(recalled.get());
        assertFalse(context.contains("长期记忆召回"));
    }

    private ChatMessage message(Long id, String role, String content) {
        return ChatMessage.builder().id(id).role(role).content(content).build();
    }
}
