package cn.bugstack.ai.trigger.service.memory;

import java.util.List;

public interface MemoryCandidateModelClient {
    Extraction extract(ExtractionRequest request);

    record ExtractionRequest(String agentId, String sessionId, String modelId, String context) { }
    record Extraction(boolean eligible, String memoryType, String memoryKey, String title,
                      String summary, String contentJson, int confidence, List<Evidence> evidence) { }
    record Evidence(Long messageId, String quote) { }
}
