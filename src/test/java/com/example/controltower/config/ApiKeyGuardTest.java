package com.example.controltower.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 대체 키가 있는 테스트 설정이 아니라 실제 application.yaml을 읽는다.
@DisabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@DisabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class ApiKeyGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yaml")
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(ApiKeyGuard.class);

    @Test
    void missingAnthropicKeyFailsStartup() {
        runner.withPropertyValues("GEMINI_API_KEY=x").run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("Could not resolve placeholder 'ANTHROPIC_API_KEY'"));
    }

    @Test
    void missingGeminiKeyFailsStartup() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=x").run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("Could not resolve placeholder 'GEMINI_API_KEY'"));
    }

    @Test
    void presentKeysStart() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=x", "GEMINI_API_KEY=x")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
