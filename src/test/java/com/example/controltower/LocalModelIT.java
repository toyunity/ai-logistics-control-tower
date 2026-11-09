package com.example.controltower;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;

// 답변은 로컬 Ollama, 임베딩은 실제 Gemini로 같은 검증을 돈다. Claude 비용 없이 실제 모델의 도구 선택을 본다.
// Gemini 키와 실행 중인 Ollama가 있을 때만 돈다. 로컬 모델은 느려 제한 시간을 넉넉히 둔다.
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIf("ollamaRunning")
@Timeout(600)
class LocalModelIT extends RealModelChecks {

    static boolean ollamaRunning() {
        String base = System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434");
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            return http.send(HttpRequest.newBuilder(URI.create(base + "/api/version")).build(), BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
