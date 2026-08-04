package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.po.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseEvidenceGateTest {

    private final AnalysisResultParser parser = new AnalysisResultParser();
    private final CaseEvidenceGate gate = new CaseEvidenceGate();

    @Test
    void rejectsSingleCharacterAsNotEligible() {
        var result = parser.parse("""
                {"decision":"NOT_ELIGIBLE","skill":{"id":"","ruleIds":[],"matchScore":0},"facts":{},"evidence":[],
                 "missingInformation":[],"severity":"P3","confidence":99,"reason":"测试输入"}
                """);

        assertEquals("NOT_ELIGIBLE", gate.evaluate("inventory", List.of(message(1, "user", "1")), result,
                bound(), existing()).state());
    }

    @Test
    void keepsVagueInventoryFeedbackInNeedMoreInfo() {
        var result = parser.parse("""
                {"decision":"NEED_MORE_INFO","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":80},
                 "facts":{"subject":"DDR5 商品","expected":"可售","actual":"商品缺失","impact":""},
                 "evidence":[{"messageId":1,"role":"user","quote":"DDR5 商品缺失，希望补货","supports":["subject","actual"]}],
                 "missingInformation":["影响"],"severity":"P2","confidence":60,"reason":"缺少影响"}
                """);

        assertEquals("NEED_MORE_INFO", gate.evaluate("inventory", List.of(message(1, "user", "DDR5 商品缺失，希望补货")),
                result, bound(), existing()).state());
    }

    @Test
    void promotesCompleteTwoMessageBusinessEvidence() {
        var result = parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":92},
                 "facts":{"subject":"DDR5 商品","expected":"应出现在可售列表","actual":"商品缺失","impact":"用户无法下单"},
                 "evidence":[{"messageId":1,"role":"user","quote":"DDR5 商品在列表里缺失","supports":["subject","actual"]},
                             {"messageId":2,"role":"tool","quote":"库存查询确认该商品没有可售记录","supports":["actual","impact"]}],
                 "missingInformation":[],"severity":"P2","confidence":88,"reason":"规则命中"}
                """);

        var decision = gate.evaluate("inventory", List.of(
                message(1, "user", "DDR5 商品在列表里缺失"),
                toolMessage(2, "库存查询确认该商品没有可售记录")), result, bound(), existing());

        assertEquals("CANDIDATE_CASE", decision.state());
        assertEquals(2, decision.acceptedEvidence().size());
        assertTrue(decision.serverScore() >= 75);
    }

    @Test
    void assistantOnlyEvidenceCannotPromote() {
        var result = parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":99},
                 "facts":{"subject":"商品","expected":"可售","actual":"缺失","impact":"无法下单"},
                 "evidence":[{"messageId":1,"role":"assistant","quote":"系统存在库存异常","supports":["actual","impact"]}],
                 "missingInformation":[],"severity":"P1","confidence":99,"reason":"助手推测"}
                """);

        assertEquals("NEED_MORE_INFO", gate.evaluate("inventory", List.of(message(1, "assistant", "系统存在库存异常")),
                result, bound(), existing()).state());
    }

    @Test
    void unboundSkillCannotPromote() {
        var result = parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"other-skill","ruleIds":["other.rule.v1"],"matchScore":99},
                 "facts":{"subject":"商品","expected":"可售","actual":"缺失","impact":"无法下单"},
                 "evidence":[{"messageId":1,"role":"user","quote":"商品缺失，无法下单","supports":["actual","impact"]}],
                 "missingInformation":[],"severity":"P1","confidence":99,"reason":"越权 Skill"}
                """);

        assertEquals("NEED_MORE_INFO", gate.evaluate("inventory", List.of(message(1, "user", "商品缺失，无法下单")),
                result, bound(), existing()).state());
    }

    @Test
    void repeatedEvidenceIsDuplicateNoOp() {
        var result = parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":92},
                 "facts":{"subject":"DDR5 商品","expected":"应可售","actual":"缺失","impact":"无法下单"},
                 "evidence":[{"messageId":1,"role":"user","quote":"DDR5 商品缺失，无法下单","supports":["subject","actual","impact"]},
                             {"messageId":2,"role":"tool","quote":"库存结果确认缺失","supports":["actual"]}],
                 "missingInformation":[],"severity":"P2","confidence":90,"reason":"重复"}
                """);
        var first = gate.evaluate("inventory", List.of(message(1, "user", "DDR5 商品缺失，无法下单"),
                toolMessage(2, "库存结果确认缺失")), result, bound(), existing());

        assertEquals("CANDIDATE_CASE", first.state());
        assertEquals("DUPLICATE", gate.evaluate("inventory", List.of(message(1, "user", "DDR5 商品缺失，无法下单"),
                toolMessage(2, "库存结果确认缺失")), result, bound(),
                new CaseEvidenceGate.ExistingCaseContext(first.evidenceFingerprint(), true)).state());
    }

    private CaseEvidenceGate.BoundSkillContext bound() {
        return new CaseEvidenceGate.BoundSkillContext("inventory", "inventory-feedback-agent",
                Set.of("inventory.stock-gap.v1"), Set.of("inventory-feedback-mcp"));
    }

    @Test
    void doesNotPromoteWhenAgentHasNoBoundMcp() {
        var result = parser.parse("""
                {"decision":"CANDIDATE_CASE","skill":{"id":"inventory-feedback-agent","ruleIds":["inventory.stock-gap.v1"],"matchScore":92},
                 "facts":{"subject":"DDR5","expected":"可售","actual":"缺货","impact":"用户无法下单"},
                 "evidence":[{"messageId":1,"role":"user","quote":"DDR5 商品缺货","supports":["subject","actual"]},
                             {"messageId":2,"role":"tool","quote":"库存结果确认缺货","supports":["actual","impact"]}],
                 "missingInformation":[],"severity":"P1","confidence":90,"reason":"命中规则"}
                """);

        assertEquals("NEED_MORE_INFO", gate.evaluate("inventory", List.of(
                message(1, "user", "DDR5 商品缺货"), toolMessage(2, "库存结果确认缺货")), result,
                new CaseEvidenceGate.BoundSkillContext("inventory", "inventory-feedback-agent",
                        Set.of("inventory.stock-gap.v1"), Set.of()), existing()).state());
    }

    private CaseEvidenceGate.ExistingCaseContext existing() {
        return new CaseEvidenceGate.ExistingCaseContext("", false);
    }

    private ChatMessage message(long id, String role, String content) {
        return ChatMessage.builder().id(id).sessionId("session-1").agentId("inventory")
                .role(role).content(content).build();
    }

    private ChatMessage toolMessage(long id, String content) {
        return ChatMessage.builder().id(id).sessionId("session-1").agentId("inventory")
                .role("tool").toolName("search_feedback").content(content).build();
    }
}
