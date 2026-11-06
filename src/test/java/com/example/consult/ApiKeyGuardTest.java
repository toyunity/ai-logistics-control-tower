package com.example.consult;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loads the real application.yaml, not the test properties with their fallback keys. */
@DisabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@DisabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ApiKeyGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yaml")
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(ApiKeyGuard.class);

    @Test
    void missingAnthropicKeyFailsStartup() {
        runner.withPropertyValues("OPENAI_API_KEY=x").run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("Could not resolve placeholder 'ANTHROPIC_API_KEY'"));
    }

    @Test
    void missingOpenAiKeyFailsStartup() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=x").run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("Could not resolve placeholder 'OPENAI_API_KEY'"));
    }

    @Test
    void presentKeysStart() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=x", "OPENAI_API_KEY=x")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
