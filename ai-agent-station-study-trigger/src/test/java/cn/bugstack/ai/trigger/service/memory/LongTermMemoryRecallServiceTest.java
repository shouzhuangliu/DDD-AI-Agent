package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryProfileDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LongTermMemoryRecallServiceTest {

    private final LongTermMemoryPort longTermMemoryPort = mock(LongTermMemoryPort.class);
    private final IMemorySummaryDao memorySummaryDao = mock(IMemorySummaryDao.class);
    private final IAgentMemoryProfileDao profileDao = mock(IAgentMemoryProfileDao.class);
    private final MemoryQueryAdmissionPolicy memoryQueryAdmissionPolicy = new MemoryQueryAdmissionPolicy();
    private final LongTermMemoryRecallService service = new LongTermMemoryRecallService(
            longTermMemoryPort, profileDao, memoryQueryAdmissionPolicy);

    @Test
    void recallOnlyReturnsPublishedLongTermMemoriesForRequestedAgent() {
        when(longTermMemoryPort.retrieve("cs", "cs", "DDR5 补货", 5)).thenReturn(List.of(
                new LongTermMemoryPort.MemoryFact("cs", "cs", "AGENT_PROFILE",
                        "DDR5 内存缺货时需要沉淀补货反馈", "", "vector", "case-1", 2),
                new LongTermMemoryPort.MemoryFact("ops", "ops", "AGENT_PROFILE",
                        "运维 Agent 的缓存告警", "", "vector", "case-ops", 1)
        ));
        when(memorySummaryDao.queryByAgent("cs", 5)).thenReturn(List.of(
                MemorySummary.builder().agentId("cs").sessionId("sess-1")
                        .summary("用户反馈 DDR5 内存商品空缺，希望补货")
                        .status("ACTIVE").createdAt(LocalDateTime.of(2026, 7, 30, 10, 0)).build(),
                MemorySummary.builder().agentId("ops").sessionId("sess-ops")
                        .summary("其它 Agent 数据")
                        .status("ACTIVE").createdAt(LocalDateTime.of(2026, 7, 30, 10, 1)).build()
        ));

        List<LongTermMemoryRecallService.MemoryRecallItem> recalls = service.recall("cs", "DDR5 补货", 5);

        assertEquals(1, recalls.size());
        assertTrue(recalls.stream().allMatch(item -> "cs".equals(item.agentId())));
        assertTrue(recalls.stream().anyMatch(item -> "case-1".equals(item.sourceId())));
        assertTrue(recalls.stream().noneMatch(item -> "SESSION_SUMMARY".equals(item.sourceType())));
        verifyNoInteractions(memorySummaryDao);
    }

    @Test
    void recallDoesNotReturnUnresolvedCandidateCaseFromProfile() {
        when(longTermMemoryPort.retrieve("cs", "cs", "缓存不一致", 5)).thenReturn(List.of());
        when(profileDao.queryLatest("cs")).thenReturn(AgentMemoryProfile.builder()
                .agentId("cs")
                .version(3)
                .profileJson("""
                        {
                          "failure_patterns": [
                            {"caseId":"case-ok","text":"缓存不一致已解决，需要核对库存写入链路","status":"RESOLVED"},
                            {"caseId":"case-candidate","text":"候选 Case 不应该进入长期召回","status":"CANDIDATE"}
                          ],
                          "business_rules": [],
                          "resolution_patterns": [],
                          "capabilities": [],
                          "preferences": []
                        }
                        """)
                .sourceCaseIds("case-ok,case-candidate")
                .build());

        List<LongTermMemoryRecallService.MemoryRecallItem> recalls = service.recall("cs", "缓存不一致", 5);

        assertEquals(1, recalls.size());
        assertEquals("case-ok", recalls.getFirst().sourceId());
        assertTrue(recalls.getFirst().summary().contains("库存写入链路"));
    }

    @Test
    void trivialQueryDoesNotTriggerRecall() {
        List<LongTermMemoryRecallService.MemoryRecallItem> recalls = service.recall("cs", "1", 5);

        assertTrue(recalls.isEmpty());
        verifyNoInteractions(longTermMemoryPort, memorySummaryDao, profileDao);
    }
}
