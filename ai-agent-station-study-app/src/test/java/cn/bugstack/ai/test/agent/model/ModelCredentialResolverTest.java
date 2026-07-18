package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.domain.agent.service.armory.ModelCredentialResolver;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModelCredentialResolverTest {

    @Test
    public void resolvesEnvironmentReference() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SENSENOVA_API_KEY", "secret-value");
        ModelCredentialResolver resolver = new ModelCredentialResolver(environment);

        assertEquals("secret-value", resolver.resolve("${SENSENOVA_API_KEY}"));
        assertTrue(resolver.isConfigured("${SENSENOVA_API_KEY}"));
    }

    @Test
    public void missingEnvironmentReferenceIsUnavailable() {
        ModelCredentialResolver resolver = new ModelCredentialResolver(new MockEnvironment());

        assertNull(resolver.resolve("${SENSENOVA_API_KEY}"));
        assertFalse(resolver.isConfigured("${SENSENOVA_API_KEY}"));
    }

    @Test
    public void acceptsExistingDatabaseCredentialDuringMigration() {
        ModelCredentialResolver resolver = new ModelCredentialResolver(new MockEnvironment());

        assertEquals("legacy-local-key", resolver.resolve("legacy-local-key"));
    }
}
