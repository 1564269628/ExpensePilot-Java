package com.expensepilot.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Configure the OpenAI-compatible Groq client before Spring AI auto-configuration.
 *
 * <p>Base64 is an encoding, not encryption. It is deliberately limited to the
 * local profile; production should obtain secrets from a proper secret store.</p>
 */
public final class LocalGroqKeyEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        // ConfigDataEnvironmentPostProcessor must first load application-local.yml.
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
        String configuredBase64 = environment.getProperty(
                "expensepilot.groq.api-key-base64");

        String key;
        if (StringUtils.hasText(explicitKey)) {
            key = explicitKey.trim();
        }
        else if (StringUtils.hasText(configuredBase64)) {
            try {
                key = new String(
                        Base64.getDecoder().decode(configuredBase64.trim()),
                        StandardCharsets.UTF_8);
            }
            catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Invalid expensepilot.groq.api-key-base64 configuration",
                        exception);
            }
        }
        else {
            throw new IllegalStateException(
                    "Local profile requires a Groq API key (Base64 config or GROQ_API_KEY)");
        }

        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("Decoded Groq API key is empty");
        }

        // Property source has priority over the generic application.yml placeholder.
        environment.getPropertySources().addFirst(new MapPropertySource(
                "expensepilotLocalGroqKey",
                Map.of("spring.ai.openai.api-key", key)));
    }
}
