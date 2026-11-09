package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.support.TestInfraConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestInfraConfig.class, RealBeansStubIT.GeminiAtStub.class})
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
        r.add("spring.ai.anthropic.api-key", () -> "test");
        r.add("spring.ai.google.genai.embedding.api-key", () -> "test");
    }

    // Gemini SDK는 주소를 바꾸는 설정 키가 없어, 연결 정보 빈을 스텁 주소를 쓰는 실제 클라이언트로 바꾼다.
    @TestConfiguration
    static class GeminiAtStub {
        @Bean
        GoogleGenAiEmbeddingConnectionDetails stubConnection() {
            String url = "http://127.0.0.1:" + stub.getAddress().getPort();
            Client client = Client.builder()
                    .apiKey("test")
                    .httpOptions(HttpOptions.builder().baseUrl(url).build())
                    .build();
            return GoogleGenAiEmbeddingConnectionDetails.builder().apiKey("test").genAiClient(client).build();
        }
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    private static void handle(HttpExchange x) throws IOException {
        var captured = new Captured(x.getRequestHeaders(), new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (x.getRequestURI().getPath().contains("mbedContents")) {
            EMBEDDINGS.add(captured);
            respond(x, "application/json", embeddingResponse(JSON.readTree(captured.body()).get("requests").size()));
        } else {
            MESSAGES.add(captured);
            String body = captured.body();
            respond(x, "text/event-stream", body.contains("tool_result") ? AFTER_TOOL
                    : body.contains(TOOL_MARKER) ? TOOL_USE : THINKING_THEN_WHITESPACE_DELTA);
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
        String values = "[1.0" + ",0.0".repeat(1535) + "]";
        List<String> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            embeddings.add("{\"values\":" + values + "}");
        }
        return "{\"embeddings\":[" + String.join(",", embeddings) + "]}";
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

    private static final String TOOL_MARKER = "정비 요청 왕복 확인";

    private static final String TOOL_USE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"요청합니다 "}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"proposeMaintenance","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"equipmentId\\":\\"CV-2\\","}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"summary\\":\\"벨트 소음\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String AFTER_TOOL = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"카드를 확인해 주세요."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @LocalServerPort int port;
    @MockitoSpyBean ProposeCommand propose;
    @MockitoSpyBean ChatMemory chatMemory;

    @Test
    void documentEmbeddingsCarryOnlyTitleAndBody() {
        JsonNode request = JSON.readTree(EMBEDDINGS.getFirst().body()).get("requests").get(0);
        assertThat(request.at("/content/parts/0/text").asString())
                .contains("제목: 가상 라인 구성 개요\n내용: 이 라인은 시뮬레이터 전용 가상 현장이다.");
        assertThat(request.get("model").asString()).endsWith("gemini-embedding-001");
        assertThat(request.get("outputDimensionality").asInt()).isEqualTo(1536);
        assertThat(EMBEDDINGS.toString()).doesNotContain("hash:", "category:");
        assertThat(EMBEDDINGS.getFirst().headers().getFirst("x-goog-api-key")).isEqualTo("test");
    }

    private static String post(HttpClient http, String url, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                BodyHandlers.ofString()).body();
    }

    private static void assertNoForbiddenOptions(JsonNode body) {
        for (String rejectedByApi : List.of("temperature", "top_p", "top_k", "thinking")) {
            assertThat(body.has(rejectedByApi)).as(rejectedByApi).isFalse();
        }
    }

    @Test
    void toolUseRoundTripKeepsIdentityOutOfTheRequest() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager())
                .build();
        post(http, "http://localhost:" + port + "/api/session", "{\"accountId\":\"operator\"}");
        MESSAGES.clear();
        // 컨트롤러의 subscribeOn은 구독과 요청만 옮긴다. 도구 실행과 메모리 저장은 SDK가 청크를 내보내는 스레드에서 돈다.
        AtomicReference<String> toolThread = new AtomicReference<>();
        AtomicReference<String> memoryThread = new AtomicReference<>();
        doAnswer(inv -> {
            toolThread.set(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(propose).propose(any(), any());
        doAnswer(inv -> {
            memoryThread.set(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(chatMemory).add(anyString(), anyList());
        int embeddingsBefore = EMBEDDINGS.size();

        String sse = post(http, "http://localhost:" + port + "/api/chat/stream", "{\"message\":\"" + TOOL_MARKER + "\"}");

        assertThat(sse.lines().filter(l -> l.startsWith("event:")).toList()).containsExactly(
                "event:meta", "event:sources", "event:delta", "event:delta", "event:action", "event:done");
        assertThat(sse).contains("\"kind\":\"MAINTENANCE\"").contains("\"targetId\":\"CV-2\"");
        // 운영 설정의 도구 advisor 순서가 RAG 안쪽이어야 두 번의 모델 호출에 검색이 한 번이다.
        assertThat(EMBEDDINGS.size() - embeddingsBefore).isEqualTo(1);
        assertThat(MESSAGES).hasSize(2);

        JsonNode first = JSON.readTree(MESSAGES.get(0).body());
        assertThat(first.get("tools")).hasSize(8);
        assertThat(StreamSupport.stream(first.get("tools").spliterator(), false).map(t -> t.get("name").asString()))
                .containsExactlyInAnyOrder("getPlantStatus", "getEquipment", "getTimeline", "diagnose",
                        "proposeRestart", "proposeDivert", "proposeReassign", "proposeMaintenance");
        assertThat(first.toString()).contains("\"conveyorId\"").doesNotContain("arg0");

        JsonNode second = JSON.readTree(MESSAGES.get(1).body());
        JsonNode toolResult = StreamSupport.stream(second.get("messages").spliterator(), false)
                .flatMap(m -> m.get("content").isArray()
                        ? StreamSupport.stream(m.get("content").spliterator(), false)
                        : java.util.stream.Stream.<JsonNode>empty())
                .filter(c -> c.get("type").asString().equals("tool_result"))
                .findFirst()
                .orElseThrow();
        assertThat(toolResult.get("tool_use_id").asString()).isEqualTo("toolu_1");
        assertThat(toolResult.toString()).contains("OK").contains("MAINTENANCE").doesNotContain("벨트 소음");

        for (Captured request : MESSAGES) {
            assertThat(request.body()).doesNotContain("operator").doesNotContain("turnScope");
            assertNoForbiddenOptions(JSON.readTree(request.body()));
        }
        for (String thread : List.of(toolThread.get(), memoryThread.get())) {
            assertThat(thread).doesNotStartWith("webflux-http-").doesNotStartWith("reactor-http-");
        }
    }

    @Test
    void chatStreamSendsTheConfiguredRequestAndMapsTheReply() throws Exception {
        MESSAGES.clear();
        String sse = post(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                "http://localhost:" + port + "/api/chat/stream", "{\"message\":\"알람 205가 뜨면 어떻게 하나요?\"}");

        assertThat(sse.lines().filter(l -> l.startsWith("event:")).toList()).containsExactly(
                "event:meta", "event:sources", "event:delta", "event:delta", "event:delta", "event:done");
        assertThat(sse.lines().filter(l -> l.startsWith("data:{\"text\"")).toList()).containsExactly(
                "data:{\"text\":\"7일\"}", "data:{\"text\":\" \"}", "data:{\"text\":\"이내입니다.\"}");

        Captured request = MESSAGES.getFirst();
        JsonNode body = JSON.readTree(request.body());
        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.at("/output_config/effort").asString()).isEqualTo("low");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(8000);
        assertNoForbiddenOptions(body);
        // 적용된 타임아웃은 이 헤더로만 확인할 수 있다.
        assertThat(request.headers().getFirst("X-stainless-timeout")).isEqualTo("600");
    }
}
