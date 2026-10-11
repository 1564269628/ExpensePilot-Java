package com.expensepilot.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Map;

/**
 * Inject the decrypted local Groq credential before Spring AI auto-configuration.
 *
 * <p>This is for development only. Encrypting a credential with a password
 * available in the same public repository does not make it confidential.</p>
 */
public final class LocalGroqKeyEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        // application-local.yml must be loaded by ConfigData processing first.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {

        if (!Arrays.asList(environment.getActiveProfiles()).contains("local")
                || !environment.getProperty(
                        "expensepilot.local.enabled", Boolean.class, false)) {
            return;
        }

        String explicitKey = environment.getProperty("GROQ_API_KEY");
        String encryptedKey = environment.getProperty(
                "expensepilot.groq.encrypted-key");

        final String key;
        if (StringUtils.hasText(explicitKey)) {
            key = explicitKey.trim();
        }
        else if (StringUtils.hasText(encryptedKey)) {
            String password = environment.getProperty(
                    "expensepilot.groq.decryption-password");
            key = GroqTokenCipher.decrypt(encryptedKey, password);
        }
        else {
            throw new IllegalStateException(
                    "Local profile requires Groq encrypted-key or GROQ_API_KEY");
        }

        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("Decoded Groq API key is empty");
        }

        environment.getPropertySources().addFirst(new MapPropertySource(
                "expensepilotLocalGroqKey",
                Map.of("spring.ai.openai.api-key", key)));
    }
}
