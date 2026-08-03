package cn.bugstack.ai.trigger.service.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultParserStructuredTest {

    private final AnalysisResultParser parser = new AnalysisResultParser();

    @Test
    void parsesStructuredCandidateWithSkillFactsAndEvidence() {
        var result = parser.parse("""
                {
                  "decision":"CANDIDATE_CASE",
                  "skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":92},
                  "facts":{"subject":"DDR5 商品","expected":"应出现在可售列表","actual":"商品缺失","impact":"用户无法下单","timeRange":"今天","scope":"单个 SKU"},
                  "evidence":[{"messageId":101,"role":"user","quote":"DDR5 商品在列表里缺失，用户无法下单","supports":["subject","actual","impact"]}],
                  "missingInformation":[],"severity":"P1","confidence":88,"reason":"命中库存缺货规则并有用户原话"
                }
                """);

        assertEquals("CANDIDATE_CASE", result.decision());
        assertEquals("inventory-feedback-agent", result.skill().id());
        assertEquals("inventory.stock-gap.v1", result.skill().ruleIds().getFirst());
        assertEquals("DDR5 商品", result.facts().subject());
        assertEquals(101L, result.evidence().getFirst().messageId());
        assertEquals("user", result.evidence().getFirst().role());
    }

    @Test
    void rejectsCandidateWithoutRuleIds() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"inventory-feedback-agent","ruleIds":[],"matchScore":90},
                 "facts":{"subject":"商品","expected":"可售","actual":"缺失","impact":"无法下单"},
                 "evidence":[{"messageId":1,"role":"user","quote":"商品缺失，无法下单","supports":["actual"]}],
                 "missingInformation":[],"severity":"P1","confidence":90,"reason":"x"}
                """));
    }

    @Test
    void keepsAssistantEvidenceForGateToReject() {
        var result = parser.parse("""
                {"decision":"NEED_MORE_INFO","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":80},
                 "facts":{"subject":"商品","expected":"可售","actual":"缺失","impact":""},
                 "evidence":[{"messageId":2,"role":"assistant","quote":"库存存在异常","supports":["actual"]}],
                 "missingInformation":["影响"],"severity":"P2","confidence":40,"reason":"缺少业务影响"}
                """);

        assertEquals("assistant", result.evidence().getFirst().role());
        assertTrue(result.facts().impact().isBlank());
    }

    @Test
    void rejectsNotEligibleWithEvidence() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"decision":"NOT_ELIGIBLE","skill":{"id":"","ruleIds":[],"matchScore":0},
                 "facts":{},"evidence":[{"messageId":1,"role":"user","quote":"1","supports":[]}],
                 "missingInformation":[],"severity":"P3","confidence":99,"reason":"测试"}
                """));
    }

    @Test
    void legacyOutputIsMarkedUnverified() {
        var result = parser.parse("""
                {"signals":[],"cases":[{"title":"库存不足","summary":"库存低于安全线","caseType":"QUALITY","severity":"HIGH",
                 "importance":80,"confidence":90,"criticalRisk":false,"businessRelated":true,"businessRelevance":90,
                 "evidenceScore":80,"businessEvidence":true,"evidenceSource":"SKILL_RESULT","promoteToCase":true,
                 "historicalHighRiskMatch":false,"reason":"旧契约"}]}
                """);

        assertEquals("LEGACY_UNVERIFIED", result.decision());
        assertEquals(1, result.cases().size());
        assertTrue(result.evidence().isEmpty());
    }
}
