package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.service.operations.CaseScoringService;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CaseScoringServiceTest {

    private final CaseScoringService service = new CaseScoringService();

    @Test
    public void calculatesExplainableWeightedScore() {
        CaseScoringService.CaseScoreBreakdown score = service.score(
                new CaseScoringService.CaseScoreInput(80, 60, 40, 100, 50, 20, 90, false, false));

        assertEquals(64.5d, score.total(), 0.001d);
        assertEquals(20d, score.severityContribution(), 0.001d);
        assertEquals(12d, score.negativeFeedbackContribution(), 0.001d);
        assertEquals(6d, score.frequencyContribution(), 0.001d);
        assertEquals(15d, score.importanceContribution(), 0.001d);
    }

    @Test
    public void criticalRiskHasPriorityFloor() {
        CaseScoringService.CaseScoreBreakdown score = service.score(
                new CaseScoringService.CaseScoreInput(10, 0, 0, 0, 0, 0, 10, true, false));

        assertEquals(85d, score.total(), 0.001d);
        assertTrue(score.priorityFloorApplied());
    }

    @Test
    public void resolvedCaseDecaysAndScoreStaysBounded() {
        CaseScoringService.CaseScoreBreakdown score = service.score(
                new CaseScoringService.CaseScoreInput(200, 200, 200, 200, 200, 200, 200, false, true));

        assertEquals(65d, score.total(), 0.001d);
    }
}
