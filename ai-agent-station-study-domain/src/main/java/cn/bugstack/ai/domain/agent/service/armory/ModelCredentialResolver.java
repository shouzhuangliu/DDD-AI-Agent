package cn.bugstack.ai.domain.agent.service.armory;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ModelCredentialResolver {

    private static final Pattern ENV_REFERENCE = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)}$");

    private final Environment environment;

    public ModelCredentialResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return null;
        }
        String candidate = configuredValue.trim();
        Matcher matcher = ENV_REFERENCE.matcher(candidate);
        String resolved = matcher.matches() ? environment.getProperty(matcher.group(1)) : candidate;
        return resolved == null || resolved.isBlank() ? null : resolved.trim();
    }

    public boolean isConfigured(String configuredValue) {
        return resolve(configuredValue) != null;
    }
}
