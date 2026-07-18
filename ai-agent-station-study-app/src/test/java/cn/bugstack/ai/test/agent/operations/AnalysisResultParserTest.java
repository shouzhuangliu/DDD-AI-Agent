package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.trigger.service.analysis.AnalysisResultParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnalysisResultParserTest {

    private final AnalysisResultParser parser = new AnalysisResultParser();

    @Test
    public void parsesStrictStructuredAnalysis() {
        String json = "{\"signals\":[{\"type\":\"TOOL_FAILURE\",\"severity\":\"HIGH\",\"confidence\":92,\"summary\":\"search failed\",\"rationale\":\"tool returned error\"}],"
                + "\"cases\":[{\"title\":\"Search MCP failure\",\"summary\":\"Search requests fail\",\"caseType\":\"BUG\",\"severity\":\"HIGH\",\"importance\":80,\"confidence\":90,\"criticalRisk\":false,\"reason\":\"reproducible tool error\"}]}";

        AnalysisResultParser.AnalysisResult result = parser.parse(json);

        assertEquals(1, result.signals().size());
        assertEquals("TOOL_FAILURE", result.signals().get(0).type());
        assertEquals(1, result.cases().size());
        assertEquals(80d, result.cases().get(0).importance(), 0.001d);
    }

    @Test
    public void parsesBusinessRelevanceEvidenceAndPromotionDecision() {
        String json = "{\"signals\":[],"
                + "\"cases\":[{\"title\":\"退款规则缺失\",\"summary\":\"售后 Agent 缺少退款审批规则\",\"caseType\":\"QUALITY\",\"severity\":\"HIGH\",\"importance\":85,\"confidence\":88,\"criticalRisk\":false,\"businessRelated\":true,\"businessRelevance\":91,\"evidenceScore\":77,\"promoteToCase\":true,\"historicalHighRiskMatch\":false,\"reason\":\"用户明确反馈退款审批规则缺失\"}]}";

        AnalysisResultParser.AnalysisResult result = parser.parse(json);

        AnalysisResultParser.CaseCandidate candidate = result.cases().get(0);
        assertTrue(candidate.businessRelated());
        assertEquals(91d, candidate.businessRelevance(), 0.001d);
        assertEquals(77d, candidate.evidenceScore(), 0.001d);
        assertTrue(candidate.promoteToCase());
        assertFalse(candidate.historicalHighRiskMatch());
    }

    @Test
    public void defaultsMissingEvaluationFieldsToSafeValues() {
        String json = "{\"signals\":[],"
                + "\"cases\":[{\"title\":\"旧格式候选\",\"summary\":\"旧格式没有评测门槛字段\",\"caseType\":\"QUALITY\",\"severity\":\"MEDIUM\",\"importance\":50,\"confidence\":95,\"criticalRisk\":false,\"reason\":\"兼容旧格式\"}]}";

        AnalysisResultParser.CaseCandidate candidate = parser.parse(json).cases().get(0);

        assertFalse(candidate.businessRelated());
        assertEquals(0d, candidate.businessRelevance(), 0.001d);
        assertEquals(0d, candidate.evidenceScore(), 0.001d);
        assertFalse(candidate.promoteToCase());
    }

    @Test
    public void localizesCommonEnglishQualityCaseText() {
        String json = "{\"signals\":[{\"type\":\"IRRELEVANT_ANSWER\",\"severity\":\"LOW\",\"confidence\":85,\"summary\":\"Assistant responded with a generic execution summary\",\"rationale\":\"User asked a Chinese question but received an internal summary\"}],"
                + "\"cases\":[{\"title\":\"Irrelevant response to user query\",\"summary\":\"Assistant answered with a generic execution summary instead of addressing the user's query\",\"caseType\":\"QUALITY\",\"severity\":\"LOW\",\"importance\":40,\"confidence\":85,\"criticalRisk\":false,\"reason\":\"The answer did not address the user query\"}]}";

        AnalysisResultParser.AnalysisResult result = parser.parse(json);

        assertEquals("答非所问", result.cases().get(0).title());
        assertEquals("答复没有回应用户问题，而是返回了泛化或内部执行总结。", result.cases().get(0).summary());
        assertEquals("助手返回了泛化或内部执行总结，没有直接回应用户问题。", result.signals().get(0).summary());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMarkdownWrappedOutput() {
        parser.parse("```json\n{\"signals\":[],\"cases\":[]}\n```");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingRequiredCollections() {
        parser.parse("{\"signals\":[]}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidConfidence() {
        parser.parse("{\"signals\":[{\"type\":\"X\",\"severity\":\"LOW\",\"confidence\":120,\"summary\":\"x\",\"rationale\":\"x\"}],\"cases\":[]}");
    }
}
