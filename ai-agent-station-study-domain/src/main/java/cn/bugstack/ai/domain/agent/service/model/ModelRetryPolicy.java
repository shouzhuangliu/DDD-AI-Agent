package cn.bugstack.ai.domain.agent.service.model;

import org.springframework.retry.support.RetryTemplate;

/** Prevents a provider client from adding a second retry loop around ReAct. */
public final class ModelRetryPolicy {

    private ModelRetryPolicy() {
    }

    public static RetryTemplate noRetry() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }
}
