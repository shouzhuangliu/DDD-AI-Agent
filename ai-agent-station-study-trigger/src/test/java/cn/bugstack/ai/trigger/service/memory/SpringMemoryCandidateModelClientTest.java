package cn.bugstack.ai.trigger.service.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringMemoryCandidateModelClientTest {

    @Test
    void parsesAutomaticMemoryUpdateOperationFromModelJson() {
        var extraction = SpringMemoryCandidateModelClient.parseExtraction("""
                {"operation":"UPDATE","targetMemoryId":"mem-1","eligible":true,
                 "memoryType":"BUSINESS_RULE","memoryKey":"inventory:threshold","title":"库存阈值",
                 "summary":"库存差异超过百分之二优先排查","content":{"threshold":2},"confidence":90,
                 "evidence":[{"messageId":18,"quote":"库存差异超过百分之二"}]}
                """);

        assertEquals("UPDATE", extraction.operation());
        assertEquals("mem-1", extraction.targetMemoryId());
        assertEquals("inventory:threshold", extraction.memoryKey());
    }
}
