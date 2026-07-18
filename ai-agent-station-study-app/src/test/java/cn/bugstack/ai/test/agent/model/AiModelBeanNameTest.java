package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.domain.agent.model.valobj.AiModelBeanName;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AiModelBeanNameTest {

    @Test
    public void buildsRequestScopedClientBeanName() {
        assertEquals("ai_client_3101_model_2002", AiModelBeanName.clientVariant("3101", "2002"));
    }
}
