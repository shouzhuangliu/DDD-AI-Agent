package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.RollingSummaryPolicy;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemoryStateDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import cn.bugstack.ai.infrastructure.dao.po.MemorySummary;
import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryPersistenceService;
import cn.bugstack.ai.trigger.service.memory.ShortTermMemoryService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ShortTermMemorySnapshotTest {

    @Test
    public void staleMessageCursorIsDiscardedWithoutOverwritingNewConversation() {
        IChatMessageDao messageDao = mock(IChatMessageDao.class);
        IMemorySummaryDao summaryDao = mock(IMemorySummaryDao.class);
        IMemoryStateDao stateDao = mock(IMemoryStateDao.class);
        ShortTermMemoryPersistenceService service = new ShortTermMemoryPersistenceService();
        ReflectionTestUtils.setField(service, "messageDao", messageDao);
        ReflectionTestUtils.setField(service, "summaryDao", summaryDao);
        ReflectionTestUtils.setField(service, "stateDao", stateDao);

        when(messageDao.queryBySessionId("s-1")).thenReturn(List.of(
                ChatMessage.builder().id(11L).build()));
        RollingSummaryPolicy.SummaryPlan plan = new RollingSummaryPolicy.SummaryPlan(
                true, 1L, 10L, 11L, 100);
        JSONObject result = new JSONObject();

        boolean saved = service.saveIfUnchanged("inventory", "s-1", "model",
                null, new ShortTermMemoryPersistenceService.RollingSummarySnapshot(plan, 10L, 20),
                result, "summary");

        assertFalse(saved);
        verify(summaryDao, never()).supersede(any());
        verify(summaryDao, never()).insert(any(MemorySummary.class));
        verifyNoInteractions(stateDao);
    }

    @Test
    public void unchangedSnapshotCommitsSummaryAndStructuredState() {
        IChatMessageDao messageDao = mock(IChatMessageDao.class);
        IMemorySummaryDao summaryDao = mock(IMemorySummaryDao.class);
        IMemoryStateDao stateDao = mock(IMemoryStateDao.class);
        ShortTermMemoryPersistenceService service = new ShortTermMemoryPersistenceService();
        ReflectionTestUtils.setField(service, "messageDao", messageDao);
        ReflectionTestUtils.setField(service, "summaryDao", summaryDao);
        ReflectionTestUtils.setField(service, "stateDao", stateDao);

        when(messageDao.queryBySessionId("s-1")).thenReturn(List.of(
                ChatMessage.builder().id(10L).build()));
        when(summaryDao.queryLatest("s-1")).thenReturn(null);
        RollingSummaryPolicy.SummaryPlan plan = new RollingSummaryPolicy.SummaryPlan(
                true, 1L, 10L, 11L, 100);
        JSONObject result = new JSONObject();
        result.put("goals", List.of("查询库存"));

        boolean saved = service.saveIfUnchanged("inventory", "s-1", "model",
                null, new ShortTermMemoryPersistenceService.RollingSummarySnapshot(plan, 10L, 20),
                result, "summary");

        assertTrue(saved);
        verify(summaryDao).supersede("s-1");
        verify(summaryDao).insert(any(MemorySummary.class));
        verify(stateDao).insert(any());
        verify(messageDao).updateCompressed(10L, 1);
    }

    @Test
    public void summaryResponseMustBePlainJsonAndNonEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> ShortTermMemoryService.parseAndNormalize("```json\n{\"summary\":\"库存反馈\"}\n```"));
        assertThrows(IllegalArgumentException.class,
                () -> ShortTermMemoryService.parseAndNormalize("{\"summary\":\"   \"}"));

        JSONObject oversized = new JSONObject();
        oversized.put("summary", "库存反馈");
        oversized.put("goals", java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> "x".repeat(600)).toList());
        oversized.put("unknown", "must be dropped");
        JSONObject normalized = ShortTermMemoryService.parseAndNormalize(oversized.toJSONString());
        assertEquals(20, normalized.getJSONArray("goals").size());
        assertEquals(500, normalized.getJSONArray("goals").getString(0).length());
        org.junit.jupiter.api.Assertions.assertFalse(normalized.containsKey("unknown"));
    }

    @Test
    public void unchangedSummaryVersionIsCommittedOnlyOnce() {
        IChatMessageDao messageDao = mock(IChatMessageDao.class);
        IMemorySummaryDao summaryDao = mock(IMemorySummaryDao.class);
        IMemoryStateDao stateDao = mock(IMemoryStateDao.class);
        ShortTermMemoryPersistenceService service = new ShortTermMemoryPersistenceService();
        ReflectionTestUtils.setField(service, "messageDao", messageDao);
        ReflectionTestUtils.setField(service, "summaryDao", summaryDao);
        ReflectionTestUtils.setField(service, "stateDao", stateDao);

        when(messageDao.queryBySessionId("s-1")).thenReturn(List.of(ChatMessage.builder().id(10L).build()));
        when(summaryDao.queryLatest("s-1")).thenReturn(null).thenReturn(
                MemorySummary.builder().id(100L).version(1).endMessageId(10L).build());
        RollingSummaryPolicy.SummaryPlan plan = new RollingSummaryPolicy.SummaryPlan(true, 1L, 10L, 11L, 100);
        ShortTermMemoryPersistenceService.RollingSummarySnapshot snapshot =
                new ShortTermMemoryPersistenceService.RollingSummarySnapshot(plan, 10L, 20);

        ShortTermMemoryPersistenceService.SaveResult first = service.saveIfUnchangedWithVersion(
                "inventory", "s-1", "model", null, snapshot, new JSONObject(), "库存反馈已记录");
        ShortTermMemoryPersistenceService.SaveResult second = service.saveIfUnchangedWithVersion(
                "inventory", "s-1", "model", null, snapshot, new JSONObject(), "库存反馈已记录");

        assertTrue(first.saved());
        assertEquals(1, first.version());
        assertFalse(second.saved());
        verify(summaryDao, times(1)).insert(any(MemorySummary.class));
    }
}
