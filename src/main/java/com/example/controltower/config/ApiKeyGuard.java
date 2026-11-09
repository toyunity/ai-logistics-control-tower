package com.example.controltower.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 프로퍼티 바인딩은 풀리지 않은 ${...}를 그대로 키 값으로 넘긴다. @Value는 여기서 기동을 실패시킨다.
@Component
@Profile("!demo")
public class ApiKeyGuard {

    ApiKeyGuard(
            @Value("${spring.ai.anthropic.api-key}") String anthropicKey,
            @Value("${spring.ai.google.genai.embedding.api-key}") String geminiKey) {}
}
