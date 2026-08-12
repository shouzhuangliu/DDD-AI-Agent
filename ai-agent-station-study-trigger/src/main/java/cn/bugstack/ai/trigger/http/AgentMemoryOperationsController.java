package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCandidate;
import cn.bugstack.ai.trigger.service.memory.AgentMemoryCandidateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/memory")
public class AgentMemoryOperationsController {

    private final AgentMemoryCandidateService candidateService;
    private final AgentMemoryCatalogPort catalog;

    public AgentMemoryOperationsController(AgentMemoryCandidateService candidateService,
                                           AgentMemoryCatalogPort catalog) {
        this.candidateService = candidateService;
        this.catalog = catalog;
    }

    @GetMapping("/candidates")
    public List<AgentMemoryCandidate> candidates(@PathVariable("agentId") String agentId,
                                                  @RequestParam(value = "status", defaultValue = "PENDING_REVIEW") String status,
                                                  @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return candidateService.list(agentId, status, limit);
    }

    @PostMapping("/candidates/{candidateId}/approve")
    public Map<String, Object> approve(@PathVariable("agentId") String agentId, @PathVariable("candidateId") String candidateId,
                                       @RequestBody ReviewRequest request) {
        request.validate();
        candidateService.approve(agentId, candidateId, request.actor(), request.reason());
        return Map.of("success", true, "status", "APPROVED");
    }

    @PostMapping("/candidates/{candidateId}/reject")
    public Map<String, Object> reject(@PathVariable("agentId") String agentId, @PathVariable("candidateId") String candidateId,
                                      @RequestBody ReviewRequest request) {
        request.validate();
        candidateService.reject(agentId, candidateId, request.actor(), request.reason());
        return Map.of("success", true, "status", "REJECTED");
    }

    @PostMapping("/candidates/{candidateId}/publish")
    public AgentMemoryCandidateService.PublishedMemory publish(@PathVariable("agentId") String agentId,
                                                                @PathVariable("candidateId") String candidateId,
                                                                @RequestBody ReviewRequest request) {
        request.validate();
        return candidateService.publish(agentId, candidateId, request.actor());
    }

    @PostMapping("/memories/{memoryId}/retire")
    public Map<String, Object> retire(@PathVariable("agentId") String agentId, @PathVariable("memoryId") String memoryId,
                                      @RequestBody ReviewRequest request) {
        request.validate();
        candidateService.retire(agentId, memoryId, request.actor(), request.reason());
        return Map.of("success", true, "status", "RETIRED");
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

    public record ReviewRequest(String actor, String reason) {
        void validate() {
            if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("审核人和审核理由不能为空");
            }
        }
    }
    public record MemoryContentRequest(List<String> memoryIds) { }
}
