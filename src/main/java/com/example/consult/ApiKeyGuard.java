package com.example.consult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Binding leaves an unset ${...} key as a literal credential; @Value fails startup on it instead. */
@Component
@Profile("!demo")
class ApiKeyGuard {

    ApiKeyGuard(
            @Value("${spring.ai.anthropic.api-key}") String anthropicKey,
            @Value("${spring.ai.openai.api-key}") String openAiKey) {}
}
