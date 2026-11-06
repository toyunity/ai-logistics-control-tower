package com.example.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.consult.support.TestInfraConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig.class)
@Timeout(60)
class RealBeansStubIT {

    private record Captured(Headers headers, String body) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Captured> MESSAGES = new CopyOnWriteArrayList<>();
    private static final List<Captured> EMBEDDINGS = new CopyOnWriteArrayList<>();
    private static HttpServer stub;

    @DynamicPropertySource
    static void pointAiClientsAtStub(DynamicPropertyRegistry r) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(Executors.newCachedThreadPool());
        stub.createContext("/", RealBeansStubIT::handle);
        stub.start();
        String url = "http://127.0.0.1:" + stub.getAddress().getPort();
        r.add("spring.ai.anthropic.base-url", () -> url);
        r.add("spring.ai.openai.base-url", () -> url);
        r.add("spring.ai.anthropic.api-key", () -> "test");
        r.add("spring.ai.openai.api-key", () -> "test");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    private static void handle(HttpExchange x) throws IOException {
        var captured = new Captured(x.getRequestHeaders(), new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (x.getRequestURI().getPath().contains("embeddings")) {
            EMBEDDINGS.add(captured);
            respond(x, "application/json", embeddingResponse(JSON.readTree(captured.body()).get("input").size()));
        } else {
            MESSAGES.add(captured);
            respond(x, "text/event-stream", THINKING_THEN_WHITESPACE_DELTA);
        }
    }

    private static void respond(HttpExchange x, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("content-type", contentType);
        x.sendResponseHeaders(200, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private static String embeddingResponse(int count) {
        ByteBuffer vector = ByteBuffer.allocate(1536 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f);
        String base64 = Base64.getEncoder().encodeToString(vector.array());
        List<String> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            data.add("{\"object\":\"embedding\",\"index\":%d,\"embedding\":\"%s\"}".formatted(i, base64));
        }
        return "{\"object\":\"list\",\"model\":\"text-embedding-3-small\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1},\"data\":["
                + String.join(",", data) + "]}";
    }

    private static final String THINKING_THEN_WHITESPACE_DELTA = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"7일"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"이내입니다."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @LocalServerPort int port;

    @Test
    void faqEmbeddingsCarryOnlyQuestionAndAnswer() {
        JsonNode input = JSON.readTree(EMBEDDINGS.getFirst().body()).get("input");
        assertThat(input.get(0).asString()).contains("Q: 환불은 어떻게 하나요?\nA: 상품 수령 후 7일 이내");
        assertThat(input.toString()).doesNotContain("hash:", "category:");
    }

    @Test
    void chatStreamSendsTheConfiguredRequestAndMapsTheReply() throws Exception {
        MESSAGES.clear();
        String sse = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"환불은 며칠 안에 가능한가요?\"}"))
                        .build(),
                BodyHandlers.ofString()).body();

        assertThat(sse.lines().filter(l -> l.startsWith("event:")).toList()).containsExactly(
                "event:meta", "event:sources", "event:delta", "event:delta", "event:delta", "event:done");
        assertThat(sse.lines().filter(l -> l.startsWith("data:{\"text\"")).toList()).containsExactly(
                "data:{\"text\":\"7일\"}", "data:{\"text\":\" \"}", "data:{\"text\":\"이내입니다.\"}");

        Captured request = MESSAGES.getFirst();
        JsonNode body = JSON.readTree(request.body());
        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.at("/output_config/effort").asString()).isEqualTo("low");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(8000);
        for (String rejectedByApi : List.of("temperature", "top_p", "top_k", "thinking")) {
            assertThat(body.has(rejectedByApi)).as(rejectedByApi).isFalse();
        }
        // 적용된 타임아웃은 이 헤더로만 확인할 수 있다.
        assertThat(request.headers().getFirst("X-stainless-timeout")).isEqualTo("600");
    }
}
