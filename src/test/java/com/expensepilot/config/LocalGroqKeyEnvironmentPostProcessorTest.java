package com.expensepilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalGroqKeyEnvironmentPostProcessorTest {

    private final LocalGroqKeyEnvironmentPostProcessor processor =
            new LocalGroqKeyEnvironmentPostProcessor();

    @Test
    void decodesLocalKeyBeforeSpringAiConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("expensepilot.local.enabled", "true");
        environment.setProperty(
                "expensepilot.groq.api-key-base64",
                Base64.getEncoder().encodeToString(
                        "gsk_example_local_value".getBytes(StandardCharsets.UTF_8)));

        processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("spring.ai.openai.api-key"))
                .isEqualTo("gsk_example_local_value");
    }

    @Test
    void explicitlyProvidedKeyOverridesBase64() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("expensepilot.local.enabled", "true");
        environment.setProperty("expensepilot.groq.api-key-base64", "invalid!");
        environment.setProperty("GROQ_API_KEY", "gsk_overridden");

        processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("spring.ai.openai.api-key"))
                .isEqualTo("gsk_overridden");
    }

    @Test
    void rejectsMalformedBase64WithoutPrintingItsContent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("expensepilot.local.enabled", "true");
        environment.setProperty("expensepilot.groq.api-key-base64", "invalid!");

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid expensepilot.groq.api-key-base64");
    }

    @Test
    void ignoresOtherProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("spring.ai.openai.api-key"))
                .isNull();
    }
}
