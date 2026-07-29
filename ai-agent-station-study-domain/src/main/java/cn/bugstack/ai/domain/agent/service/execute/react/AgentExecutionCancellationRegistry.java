package cn.bugstack.ai.domain.agent.service.execute.react;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理主 Agent 执行的协作式取消状态。 */
@Service
public class AgentExecutionCancellationRegistry {

    private final ConcurrentMap<String, AtomicBoolean> executions = new ConcurrentHashMap<>();

    public void register(String executionId) {
        executions.put(executionId, new AtomicBoolean(false));
    }

    public boolean cancel(String executionId) {
        AtomicBoolean cancelled = executions.get(executionId);
        return cancelled != null && cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled(String executionId) {
        AtomicBoolean cancelled = executions.get(executionId);
        return cancelled != null && cancelled.get();
    }

    public void remove(String executionId) {
        if (executionId != null) executions.remove(executionId);
    }
}
