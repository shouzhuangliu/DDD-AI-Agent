package cn.bugstack.ai.trigger.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseSummaryComposerTest {

    private final AnalysisResultParser parser = new AnalysisResultParser();
    private final CaseSummaryComposer composer = new CaseSummaryComposer();

    @Test
    void composesSummaryFromFactsRuleAndMessageIds() {
        var evaluation = parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":90},
                 "facts":{"subject":"DDR5 商品","expected":"应出现在可售列表","actual":"商品缺失","impact":"用户无法下单"},
                 "evidence":[],"missingInformation":[],"severity":"P2","confidence":88,"reason":"规则命中"}
                """);
        var output = composer.compose(evaluation,
                new CaseSummaryComposer.BoundSkillRule("inventory-feedback-agent", "inventory.stock-gap.v1", "库存缺货规则"),
                List.of(new CaseEvidenceGate.EvidenceRef(101, "user", "DDR5 商品缺失", List.of("actual")),
                        new CaseEvidenceGate.EvidenceRef(102, "operator", "库存结果确认缺失", List.of("impact"))));

        assertTrue(output.title().contains("DDR5 商品"));
        assertTrue(output.summary().contains("期望：应出现在可售列表"));
        assertTrue(output.summary().contains("实际：商品缺失"));
        assertTrue(output.summary().contains("业务影响：用户无法下单"));
        assertTrue(output.summary().contains("消息 101、102"));
        assertTrue(output.extractionReason().contains("inventory.stock-gap.v1"));
    }

    @Test
    void missingFactsProduceExplicitIncompleteSummaryWithoutInventingText() {
        var evaluation = parser.parse("""
                {"decision":"NEED_MORE_INFO","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":60},
                 "facts":{"subject":"DDR5 商品","expected":"","actual":"","impact":""},
                 "evidence":[],"missingInformation":["SKU"],"severity":"P2","confidence":20,"reason":"信息不足"}
                """);

        var output = composer.compose(evaluation, null, List.of());

        assertTrue(output.summary().contains("缺失信息：SKU"));
        assertTrue(output.summary().contains("未生成 Case"));
        assertTrue(!output.summary().contains("已确认库存异常"));
    }
}
