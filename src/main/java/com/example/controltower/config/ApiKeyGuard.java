package com.example.controltower.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

// 프로퍼티 바인딩은 풀리지 않은 ${...}를 그대로 키 값으로 넘긴다. 여기서 먼저 풀어 기동을 실패시킨다.
// local 프로필은 답변을 Ollama가 하므로 Claude 키를 요구하지 않는다.
@Component
@Profile("!demo")
public class ApiKeyGuard {

    ApiKeyGuard(Environment env) {
        if (!env.acceptsProfiles(Profiles.of("local"))) {
            env.resolveRequiredPlaceholders("${spring.ai.anthropic.api-key}");
        }
        env.resolveRequiredPlaceholders("${spring.ai.google.genai.embedding.api-key}");
    }
}
