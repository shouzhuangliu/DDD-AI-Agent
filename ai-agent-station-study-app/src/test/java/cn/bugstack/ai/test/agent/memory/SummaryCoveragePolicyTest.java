package cn.bugstack.ai.test.agent.memory;

import cn.bugstack.ai.domain.agent.service.memory.SummaryCoveragePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryCoveragePolicyTest {

    @Test
    void summaryReplacesOnlyMessagesThroughItsCoverageCursor() {
        SummaryCoveragePolicy coverage = SummaryCoveragePolicy.of(14L);

        assertTrue(coverage.covers(1L));
        assertTrue(coverage.covers(14L));
        assertFalse(coverage.covers(15L));
        assertFalse(coverage.covers(null));
    }

    @Test
    void missingOrInvalidCursorDoesNotHideOriginalMessages() {
        assertFalse(SummaryCoveragePolicy.of(null).covers(1L));
        assertFalse(SummaryCoveragePolicy.of(0L).covers(1L));
    }
}
