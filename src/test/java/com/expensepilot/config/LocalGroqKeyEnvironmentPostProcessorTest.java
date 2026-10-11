package com.expensepilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalGroqKeyEnvironmentPostProcessorTest {

    private static final String TEST_TOKEN = "synthetic-local-token-not-live";
    private static final String ENCRYPTED_FIXTURE =
            GroqTokenCipher.encrypt(TEST_TOKEN, "123456");

    private final LocalGroqKeyEnvironmentPostProcessor processor =
            new LocalGroqKeyEnvironmentPostProcessor();

    @Test
    void decryptsEncryptedKeyBeforeSpringAiConfiguration() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("expensepilot.groq.encrypted-key", ENCRYPTED_FIXTURE);
        environment.setProperty("expensepilot.groq.decryption-password", "123456");

        processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("spring.ai.openai.api-key"))
                .isEqualTo(TEST_TOKEN);
    }

    @Test
    void explicitEnvironmentKeyTakesPriority() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("expensepilot.groq.encrypted-key", "invalid!");
        environment.setProperty("GROQ_API_KEY", "manual-override-for-test");

        processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("spring.ai.openai.api-key"))
                .isEqualTo("manual-override-for-test");
    }

    @Test
    void rejectsWrongDecryptionPassword() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("expensepilot.groq.encrypted-key", ENCRYPTED_FIXTURE);
        environment.setProperty("expensepilot.groq.decryption-password", "incorrect");

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot decrypt Groq token");
    }

    @Test
    void rejectsMalformedCiphertext() {
        assertThatThrownBy(() -> GroqTokenCipher.decrypt("v1:invalid!", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding");
    }

    @Test
    void encryptionAndDecryptionRoundTrip() {
        String encrypted = GroqTokenCipher.encrypt(
                "synthetic-test-only-secret", "123456");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("synthetic-test-only-secret");
        assertThat(GroqTokenCipher.decrypt(encrypted, "123456"))
                .isEqualTo("synthetic-test-only-secret");
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

    private MockEnvironment localEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("expensepilot.local.enabled", "true");
        return environment;
    }
}
