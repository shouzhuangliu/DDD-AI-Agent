package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
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
        AgentMemoryCatalogPort memoryPort = new AgentMemoryCatalogPort() {
            @Override
            public List<MemoryIndexItem> search(String agentId, String query, int limit) {
                assertEquals("refund_agent", agentId);
                assertTrue(query.contains("退款审核"));
                assertEquals(5, limit);
                return List.of(new MemoryIndexItem(agentId, "mem-1", 1, "PUBLISHED_CASE",
                        "退款审核规则", "历史解决经验", "case-old", 0.9));
            }

            @Override
            public List<MemoryContent> getPublished(String agentId, List<String> memoryIds) {
                return List.of(new MemoryContent(agentId, "mem-1", 1, "PUBLISHED_CASE",
                        "退款审核规则", "历史解决经验",
                        "历史 Case：退款审核规则缺失会导致售后 Agent 给出不完整答案。", "case-old"));
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
        AgentMemoryCatalogPort memoryPort = new AgentMemoryCatalogPort() {
            @Override
            public List<MemoryIndexItem> search(String agentId, String query, int limit) {
                recalled.set(true);
                return List.of();
            }
            @Override public List<MemoryContent> getPublished(String agentId, List<String> memoryIds) { return List.of(); }
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
        AgentMemoryCatalogPort memoryPort = mock(AgentMemoryCatalogPort.class);
        AgentRuntimeBindingService bindingService = mock(AgentRuntimeBindingService.class);
        SkillScannerService scanner = mock(SkillScannerService.class);
        var bindings = AgentRuntimeBindingService.AgentRuntimeBindings.builder()
                .workspace(java.nio.file.Path.of("D:/agent-workspace"))
                .skillIds(List.of("inventory-feedback-agent"))
                .build();
        when(bindingService.assemble("inventory-agent", System.getProperty("user.dir"), true))
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
        assertTrue(context.contains("[BOUND BUSINESS SKILLS]"));
        assertTrue(builder.hasBoundBusinessSkills("inventory-agent"));
    }

    private ChatMessage message(Long id, String role, String content) {
        return ChatMessage.builder().id(id).role(role).content(content).build();
    }
}
