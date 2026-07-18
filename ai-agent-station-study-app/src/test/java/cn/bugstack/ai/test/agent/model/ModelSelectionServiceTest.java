package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import org.junit.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.junit.Assert.assertEquals;

public class ModelSelectionServiceTest {

    @Test
    public void blankModelKeepsDefault() {
        new ModelSelectionService(new StaticApplicationContext()).requireAvailable(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownModelIsRejected() {
        new ModelSelectionService(new StaticApplicationContext()).requireAvailable("2999");
    }

    @Test
    public void requestModelOverridesAgentDefault() {
        assertEquals("2002", ModelSelectionService.select("2002", "2001"));
    }

    @Test
    public void missingModelsUseDeepSeekDefault() {
        assertEquals("2001", ModelSelectionService.select(" ", null));
    }
}
