package cn.bugstack.ai.trigger.service.observability;

import cn.bugstack.ai.infrastructure.dao.po.AiLlmLog;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmLogObservationAssemblerTest {

    private final LlmLogObservationAssembler assembler = new LlmLogObservationAssembler();

    @Test
    void sessionLastSeenUsesNewestMessageOrLlmLogTime() {
        LocalDateTime olderLogTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        LocalDateTime newerToolTime = LocalDateTime.of(2026, 7, 30, 10, 5);
        AiLlmLog log = AiLlmLog.builder()
                .id(1L)
                .agentId("cs")
                .sessionId("sess-1")
                .createdAt(olderLogTime)
                .totalTokens(10)
                .build();
        Map<String, List<ChatMessage>> messages = Map.of("sess-1", List.of(
                ChatMessage.builder()
                        .id(11L)
                        .agentId("cs")
                        .sessionId("sess-1")
                        .role("tool")
                        .toolName("read_file")
                        .content("读取 Skill")
                        .createdAt(newerToolTime)
                        .build()
        ));

        List<LlmLogObservationAssembler.AgentGroup> result = assembler.group(List.of(log), messages::get);

        assertEquals(newerToolTime, result.getFirst().sessions().getFirst().lastSeenAt());
    }

    @Test
    void keepsOnlyMessagesBelongingToGroupedAgent() {
        AiLlmLog log = AiLlmLog.builder()
                .id(1L)
                .agentId("cs")
                .sessionId("sess-1")
                .createdAt(LocalDateTime.of(2026, 7, 30, 10, 0))
                .build();
        Map<String, List<ChatMessage>> messages = Map.of("sess-1", List.of(
                ChatMessage.builder().id(11L).agentId("cs").sessionId("sess-1").role("user").content("a").build(),
                ChatMessage.builder().id(12L).agentId("ops").sessionId("sess-1").role("user").content("b").build()
        ));

        List<LlmLogObservationAssembler.AgentGroup> result = assembler.group(List.of(log), messages::get);

        assertEquals(1, result.getFirst().sessions().getFirst().messages().size());
        assertEquals("cs", result.getFirst().sessions().getFirst().messages().getFirst().agentId());
    }
}
