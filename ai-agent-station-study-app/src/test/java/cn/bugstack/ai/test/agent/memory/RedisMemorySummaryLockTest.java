package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.MemorySummaryLock;
import cn.bugstack.ai.infrastructure.redis.RedisMemorySummaryLock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMemorySummaryLockTest {

    @Test
    void acquiresWithNxAndReleasesOnlyMatchingToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("agent:memory:summary:cs:sess-1"), anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);

        MemorySummaryLock lock = new RedisMemorySummaryLock(redis);
        MemorySummaryLock.Lease lease = lock.tryAcquire("agent:memory:summary:cs:sess-1", Duration.ofSeconds(30));

        assertNotNull(lease);
        lock.release(lease);
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(lease.key())), eq(lease.token()));
    }

    @Test
    void returnsNullWhenAnotherWorkerOwnsTheLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("agent:memory:summary:cs:sess-1"), anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(false);

        MemorySummaryLock lock = new RedisMemorySummaryLock(redis);

        assertNull(lock.tryAcquire("agent:memory:summary:cs:sess-1", Duration.ofSeconds(30)));
    }
}
