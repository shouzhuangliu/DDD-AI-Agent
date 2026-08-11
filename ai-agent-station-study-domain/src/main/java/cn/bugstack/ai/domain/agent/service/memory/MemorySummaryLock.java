package cn.bugstack.ai.domain.agent.service.memory;

import java.time.Duration;

/**
 * 会话滚动摘要的分布式租约锁抽象。
 *
 * <p>摘要属于后台任务，锁只用于避免同一 Agent 会话被多个 worker 同时刷新；
 * 业务层不应依赖具体的 Redis API。</p>
 */
public interface MemorySummaryLock {

    Lease tryAcquire(String key, Duration ttl);

    void release(Lease lease);

    record Lease(String key, String token) {
    }
}
