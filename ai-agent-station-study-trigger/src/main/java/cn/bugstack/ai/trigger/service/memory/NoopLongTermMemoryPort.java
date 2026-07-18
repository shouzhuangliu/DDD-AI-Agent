package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/** Keeps chat available when the optional long-term memory service is offline. */
@Service
@ConditionalOnProperty(prefix = "agent.memory.long-term", name = "provider", havingValue = "noop")
public class NoopLongTermMemoryPort implements LongTermMemoryPort {
    @Override public void store(MemoryFact fact) { }
    @Override public List<MemoryFact> retrieve(String agentId, String subjectId, String query, int limit) { return List.of(); }
}
