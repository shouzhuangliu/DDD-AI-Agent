package cn.bugstack.ai.infrastructure.redis;

import cn.bugstack.ai.domain.agent.service.memory.MemorySummaryLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/** Redis SET NX EX 租约锁，释放时通过 token 校验避免误删其他 worker 的锁。 */
@Component
public class RedisMemorySummaryLock implements MemorySummaryLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisMemorySummaryLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Lease tryAcquire(String key, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("lock key must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lock ttl must be positive");
        }

        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? new Lease(key, token) : null;
    }

    @Override
    public void release(Lease lease) {
        if (lease == null || !StringUtils.hasText(lease.key()) || !StringUtils.hasText(lease.token())) {
            return;
        }
        redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lease.key()), lease.token());
    }
}
