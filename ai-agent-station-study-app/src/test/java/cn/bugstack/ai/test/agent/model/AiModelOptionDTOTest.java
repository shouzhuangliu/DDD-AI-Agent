package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.api.dto.AiModelOptionDTO;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;

public class AiModelOptionDTOTest {

    @Test
    public void safeCatalogDtoHasNoCredentialField() {
        boolean hasCredentialField = Arrays.stream(AiModelOptionDTO.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase())
                .anyMatch(name -> name.contains("apikey") || name.contains("credential") || name.contains("secret"));

        assertFalse(hasCredentialField);
    }
}
