package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.trigger.service.observability.LlmLogObservationAssembler;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LlmLogObservationAssemblerTest {

    @Test
    public void groupsLogsByAgentThenSessionAndAttachesConversationTimeline() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 12, 0);
        List<AiLlmLog> logs = List.of(
                AiLlmLog.builder().id(1L).agentId("agent-a").sessionId("s-1").modelName("deepseek").mode("react").status("success").durationMs(120).totalTokens(300).createdAt(now).build(),
                AiLlmLog.builder().id(2L).agentId("agent-a").sessionId("s-1").modelName("deepseek").mode("react").status("success").durationMs(80).totalTokens(180).createdAt(now.plusSeconds(2)).build(),
                AiLlmLog.builder().id(3L).agentId("agent-b").sessionId("s-2").modelName("sensenova").mode("auto").status("success").durationMs(60).totalTokens(90).createdAt(now).build()
        );
        Map<String, List<ChatMessage>> messages = Map.of(
                "s-1", List.of(
                        ChatMessage.builder().id(10L).agentId("agent-a").sessionId("s-1").turn(1).step(1).role("user").content("查订单").createdAt(now).build(),
                        ChatMessage.builder().id(11L).agentId("agent-a").sessionId("s-1").turn(1).step(2).role("assistant").content("我先调用工具").toolCallsJson("[{}]").createdAt(now.plusSeconds(1)).build(),
                        ChatMessage.builder().id(12L).agentId("agent-a").sessionId("s-1").turn(1).step(3).role("tool").toolName("order_query").content("订单超时").createdAt(now.plusSeconds(2)).build()
                ),
                "s-2", List.of(ChatMessage.builder().id(20L).agentId("agent-b").sessionId("s-2").role("user").content("你好").createdAt(now).build())
        );

        List<LlmLogObservationAssembler.AgentGroup> groups = new LlmLogObservationAssembler()
                .group(logs, sessionId -> messages.getOrDefault(sessionId, List.of()));

        assertEquals(2, groups.size());
        assertEquals("agent-a", groups.get(0).agentId());
        assertEquals(2, groups.get(0).totalCalls());
        assertEquals(1, groups.get(0).sessions().size());
        assertEquals("s-1", groups.get(0).sessions().get(0).sessionId());
        assertEquals(2, groups.get(0).sessions().get(0).logs().size());
        assertEquals(3, groups.get(0).sessions().get(0).messages().size());
        assertEquals("tool", groups.get(0).sessions().get(0).messages().get(2).role());
        assertEquals("order_query", groups.get(0).sessions().get(0).messages().get(2).toolName());
    }
}
