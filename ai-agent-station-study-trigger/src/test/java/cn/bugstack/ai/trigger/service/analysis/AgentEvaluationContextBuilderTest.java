package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.trigger.service.memory.MemoryQueryAdmissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void includesOnlySkillsBoundToTheCurrentAgentInEvaluationContext() {
        LongTermMemoryPort memoryPort = mock(LongTermMemoryPort.class);
        AgentRuntimeBindingService bindingService = mock(AgentRuntimeBindingService.class);
        SkillScannerService scanner = mock(SkillScannerService.class);
        var bindings = AgentRuntimeBindingService.AgentRuntimeBindings.builder()
                .workspace(java.nio.file.Path.of("D:/agent-workspace"))
                .skillIds(List.of("inventory-feedback-agent"))
                .build();
        when(bindingService.assemble("inventory-agent", System.getProperty("user.dir"), false))
                .thenReturn(bindings);
        when(scanner.readSkillFromWorkDir(java.nio.file.Path.of("D:/agent-workspace").toString(), "inventory-feedback-agent"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("inventory-feedback-agent")
                        .skillName("库存反馈判断")
                        .description("库存业务规则")
                        .content("库存低于安全线时才可形成业务 Case")
                        .build());

        AgentEvaluationContextBuilder builder = new AgentEvaluationContextBuilder(
                memoryPort, new MemoryQueryAdmissionPolicy());
        ReflectionTestUtils.setField(builder, "agentRuntimeBindingService", bindingService);
        ReflectionTestUtils.setField(builder, "skillScannerService", scanner);

        String context = builder.build("inventory-agent", List.of(message(1L, "user", "1")));

        assertTrue(context.contains("[BOUND BUSINESS SKILLS]"));
        assertTrue(context.contains("inventory-feedback-agent"));
        assertTrue(context.contains("库存低于安全线时才可形成业务 Case"));
        assertTrue(builder.hasBoundBusinessSkills("inventory-agent"));
    }

    private ChatMessage message(Long id, String role, String content) {
        return ChatMessage.builder().id(id).role(role).content(content).build();
    }
}
