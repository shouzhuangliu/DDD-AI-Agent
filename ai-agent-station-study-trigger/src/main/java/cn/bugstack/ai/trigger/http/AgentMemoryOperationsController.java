package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryChangeLogDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryChangeLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Automatic Agent memory operations. Case workflow remains separately human reviewed. */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/memory")
public class AgentMemoryOperationsController {
    private final AgentMemoryLifecyclePort lifecycle;
    private final AgentMemoryCatalogPort catalog;
    private final IAgentMemoryChangeLogDao changeLogDao;

    public AgentMemoryOperationsController(AgentMemoryLifecyclePort lifecycle,
                                           AgentMemoryCatalogPort catalog,
                                           IAgentMemoryChangeLogDao changeLogDao) {
        this.lifecycle = lifecycle;
        this.catalog = catalog;
        this.changeLogDao = changeLogDao;
    }

    @PostMapping("/memories/{memoryId}/retire")
    public Map<String, Object> retire(@PathVariable("agentId") String agentId, @PathVariable("memoryId") String memoryId,
                                      @RequestBody RetireRequest request) {
        request.validate();
        lifecycle.retire(new AgentMemoryLifecyclePort.RetireCommand(agentId, memoryId, request.sourceType(),
                request.sourceId(), request.evidenceQuote(), request.reason()));
        return Map.of("success", true, "status", "RETIRED", "memoryId", memoryId);
    }

    @GetMapping("/memories/search")
    public List<AgentMemoryCatalogPort.MemoryIndexItem> search(@PathVariable("agentId") String agentId,
                                                                @RequestParam("query") String query,
                                                                @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return catalog.search(agentId, query, limit);
    }

    @PostMapping("/memories/content")
    public List<AgentMemoryCatalogPort.MemoryContent> content(@PathVariable("agentId") String agentId,
                                                               @RequestBody MemoryContentRequest request) {
        return catalog.getPublished(agentId, request == null ? List.of() : request.memoryIds());
    }

    @GetMapping("/memories/{memoryId}/audit")
    public List<AgentMemoryChangeLog> audit(@PathVariable("agentId") String agentId, @PathVariable("memoryId") String memoryId,
                                             @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return changeLogDao.queryByMemoryId(agentId, memoryId, Math.min(Math.max(limit, 1), 100));
    }

    public record RetireRequest(String sourceType, String sourceId, String evidenceQuote, String reason) {
        void validate() {
            if (blank(sourceType) || blank(sourceId) || blank(evidenceQuote) || blank(reason)) {
                throw new IllegalArgumentException("memory retire requires source, evidence and reason");
            }
        }
    }
    public record MemoryContentRequest(List<String> memoryIds) { }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
