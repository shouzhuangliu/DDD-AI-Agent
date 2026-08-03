package cn.bugstack.ai.test.agent.react;

import cn.bugstack.ai.domain.agent.service.model.ModelRetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRetryPolicyTest {

    @Test
    void doesNotRetryAProviderFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> ModelRetryPolicy.noRetry().execute(context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("429 rpm exhausted");
        }));

        assertEquals(1, attempts.get());
    }
}
