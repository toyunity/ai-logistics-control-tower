package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.example.controltower.support.TestInfraConfig;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/**
 * local 프로필의 실제 Ollama 빈을 로컬 스텁에 붙인다. Ollama 클라이언트가 Reactor Netty로 받으면 청크가 서버와 같은
 * 이벤트 루프에서 나와, 청크 뒤에 붙는 일이 모두 그 루프에서 돈다. 청크와 메모리 저장(JDBC)이 Netty 스레드가 아닌지 본다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import({TestInfraConfig.class, LocalOllamaStreamIT.GeminiAtStub.class})
@Timeout(60)
class LocalOllamaStreamIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> CHAT_REQUESTS = new CopyOnWriteArrayList<>();
    private static HttpServer stub;

    @DynamicPropertySource
    static void pointAiClientsAtStub(DynamicPropertyRegistry r) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(Executors.newCachedThreadPool());
        stub.createContext("/", LocalOllamaStreamIT::handle);
        stub.start();
        r.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        r.add("spring.ai.google.genai.embedding.api-key", () -> "test");
    }

    // Gemini SDK는 주소를 바꾸는 설정 키가 없어, 연결 정보 빈을 스텁 주소를 쓰는 실제 클라이언트로 바꾼다.
    @TestConfiguration
    static class GeminiAtStub {
        @Bean
        GoogleGenAiEmbeddingConnectionDetails stubConnection() {
            Client client = Client.builder()
                    .apiKey("test")
                    .httpOptions(HttpOptions.builder()
                            .baseUrl("http://127.0.0.1:" + stub.getAddress().getPort())
                            .build())
                    .build();
            return GoogleGenAiEmbeddingConnectionDetails.builder().apiKey("test").genAiClient(client).build();
        }
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    private static void handle(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (x.getRequestURI().getPath().contains("mbedContents")) {
            respond(x, "application/json", embeddings(JSON.readTree(body).get("requests").size()));
        } else {
            CHAT_REQUESTS.add(body);
            respond(x, "application/x-ndjson", OLLAMA_STREAM);
        }
    }

    private static void respond(HttpExchange x, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("content-type", contentType);
        x.sendResponseHeaders(200, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private static String embeddings(int count) {
        String values = "[1.0" + ",0.0".repeat(1535) + "]";
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add("{\"values\":" + values + "}");
        }
        return "{\"embeddings\":[" + String.join(",", items) + "]}";
    }

    private static final String OLLAMA_STREAM = """
            {"model":"qwen2.5:7b","created_at":"2026-11-09T00:00:00Z","message":{"role":"assistant","content":"점검 "},"done":false}
            {"model":"qwen2.5:7b","created_at":"2026-11-09T00:00:00Z","message":{"role":"assistant","content":"절차입니다."},"done":false}
            {"model":"qwen2.5:7b","created_at":"2026-11-09T00:00:01Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":1,"load_duration":1,"prompt_eval_count":1,"prompt_eval_duration":1,"eval_count":2,"eval_duration":1}
            """;

    @LocalServerPort int port;
    @MockitoSpyBean ChatMemory chatMemory;
    @MockitoSpyBean OllamaChatModel model;

    @Test
    void ollamaChunksAndMemorySaveStayOffNettyThreads() throws Exception {
        List<String> saveThreads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            saveThreads.add(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(chatMemory).add(anyString(), anyList());
        List<String> chunkThreads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> ((Flux<?>) inv.callRealMethod())
                .doOnNext(c -> chunkThreads.add(Thread.currentThread().getName())))
                .when(model).stream(any(Prompt.class));

        String sse = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"알람 205 점검 절차\"}"))
                        .build(),
                BodyHandlers.ofString()).body();

        assertThat(sse).contains("event:done").contains("절차입니다.");
        assertThat(CHAT_REQUESTS).hasSize(1);
        // 청크 뒤의 advisor와 매핑, SSE 인코딩이 서버의 이벤트 루프를 같이 쓰지 않게 한다.
        assertThat(chunkThreads).isNotEmpty().allSatisfy(LocalOllamaStreamIT::isNotNetty);
        assertThat(saveThreads).isNotEmpty().allSatisfy(LocalOllamaStreamIT::isNotNetty);
    }

    private static void isNotNetty(String thread) {
        assertThat(thread).doesNotStartWith("reactor-http-").doesNotStartWith("webflux-http-");
    }
}
