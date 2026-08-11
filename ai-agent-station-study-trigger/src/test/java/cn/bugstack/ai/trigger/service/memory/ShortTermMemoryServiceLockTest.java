package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.MemorySummaryLock;
import cn.bugstack.ai.domain.agent.service.memory.ContextBudgetPolicy;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IMemorySummaryDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortTermMemoryServiceLockTest {

    private ShortTermMemoryService service;
    private MemorySummaryLock lock;
    private IChatMessageDao messageDao;
    private IMemorySummaryDao summaryDao;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        service = new ShortTermMemoryService();
        lock = mock(MemorySummaryLock.class);
        messageDao = mock(IChatMessageDao.class);
        summaryDao = mock(IMemorySummaryDao.class);
        applicationContext = mock(ApplicationContext.class);
        ReflectionTestUtils.setField(service, "summaryLock", lock);
        ReflectionTestUtils.setField(service, "messageDao", messageDao);
        ReflectionTestUtils.setField(service, "summaryDao", summaryDao);
        ReflectionTestUtils.setField(service, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(service, "summaryLockTtlSeconds", 180L);
    }

    @Test
    void lockBusySkipsModelAndReportsRetryableResult() {
        when(lock.tryAcquire("agent:memory:summary:cs:sess-1", Duration.ofSeconds(180))).thenReturn(null);

        SummaryRefreshResult result = service.refreshIfNeeded("cs", "sess-1", "deepseek-v4-flash");

        assertEquals(SummaryRefreshResult.Status.LOCK_BUSY, result.status());
        verifyNoInteractions(messageDao, summaryDao, applicationContext);
    }

    @Test
    void releasesLeaseWhenSnapshotPreparationFails() {
        MemorySummaryLock.Lease lease = new MemorySummaryLock.Lease(
                "agent:memory:summary:cs:sess-1", "token-1");
        ContextBudgetPolicy budgetPolicy = mock(ContextBudgetPolicy.class);
        when(lock.tryAcquire(lease.key(), Duration.ofSeconds(180))).thenReturn(lease);
        when(messageDao.queryBySessionId("sess-1")).thenReturn(List.of());
        when(budgetPolicy.decide(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("context unavailable"));
        ReflectionTestUtils.setField(service, "contextBudgetPolicy", budgetPolicy);

        assertThrows(IllegalStateException.class,
                () -> service.refreshIfNeeded("cs", "sess-1", "deepseek-v4-flash"));

        org.mockito.Mockito.verify(lock).release(lease);
    }
}
