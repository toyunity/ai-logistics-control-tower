package com.example.consult;

import static com.example.consult.support.FakeChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.consult.support.FakeAiConfig;
import com.example.consult.support.FakeChatModel;
import com.example.consult.support.TestInfraConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestInfraConfig.class, FakeAiConfig.class})
@Timeout(30)
@ExtendWith(OutputCaptureExtension.class)
class ChatStreamIT {

    // h2c 스트림 리셋이 아니라 Tomcat이 알아챌 수 있는 TCP 종료가 되도록 HTTP/1.1을 쓴다.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort int port;
    @Autowired FakeChatModel chatModel;
    @Autowired JdbcTemplate jdbc;

    UUID conversationId;

    @BeforeEach
    void setUp() {
        chatModel.reset();
        conversationId = UUID.randomUUID();
    }

    @Test
    void streamsIncrementally() throws Exception {
        chatModel.enqueueStream(Flux.just(text("하나"), text("둘"), text("셋")).delayElements(Duration.ofMillis(300)));

        long[] firstDeltaAt = new long[1];
        long[] doneAt = new long[1];
        HttpResponse<Stream<String>> response = post("질문", BodyHandlers.ofLines());
        try (var lines = response.body()) {
            lines.forEach(line -> {
                if (line.equals("event:delta") && firstDeltaAt[0] == 0) {
                    firstDeltaAt[0] = System.nanoTime();
                } else if (line.equals("event:done")) {
                    doneAt[0] = System.nanoTime();
                }
            });
        }

        assertThat(firstDeltaAt[0]).as("delta received").isNotZero();
        assertThat(doneAt[0]).as("done received").isNotZero();
        assertThat(Duration.ofNanos(doneAt[0] - firstDeltaAt[0])).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void conversationPersistsAcrossTurns() throws Exception {
        chatModel.enqueueStream(Flux.just(text("첫째 답변 XYZ")));
        assertThat(names(chat("첫째 질문 ABC"))).endsWith("done");

        chatModel.enqueueStream(Flux.just(text("둘째 답변")));
        assertThat(names(chat("둘째 질문"))).endsWith("done");

        assertThat(memoryRows("USER")).isEqualTo(2);
        assertThat(memoryRows("ASSISTANT")).isEqualTo(2);

        List<Message> sent = chatModel.prompts().getLast().getInstructions();
        assertThat(textsOf(sent, MessageType.USER)).anyMatch(t -> t.contains("첫째 질문 ABC"));
        assertThat(textsOf(sent, MessageType.ASSISTANT)).contains("첫째 답변 XYZ");
    }

    @Test
    void seededFaqIsCitedInSources() throws Exception {
        chatModel.enqueueStream(Flux.just(text("7일 이내에 신청하세요.")));

        List<SseEvent> events = chat("환불하고 싶어요");

        assertThat(names(events)).containsExactly("meta", "sources", "delta", "done");
        assertThat(events.get(1).data()).contains("\"id\":\"refund-policy\"");

        String q = URLEncoder.encode("환불", StandardCharsets.UTF_8);
        HttpResponse<String> search = http.send(
                HttpRequest.newBuilder(uri("/api/faqs/search?q=" + q)).build(),
                BodyHandlers.ofString());
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains("\"id\":\"refund-policy\"");
    }

    @Test
    void disconnectThenNextTurnWorks() throws Exception {
        var cancelled = new AtomicBoolean();
        Flux<ChatResponse> slow = Flux.range(1, 50)
                .delayElements(Duration.ofMillis(100))
                .map(i -> text("조각" + i))
                .doOnCancel(() -> cancelled.set(true));
        chatModel.enqueueStream(slow);

        HttpResponse<InputStream> response = post("끊을 질문", BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            do {
                line = reader.readLine();
                assertThat(line).as("stream ended before first delta").isNotNull();
            } while (!line.equals("event:delta"));
        }

        await().atMost(Duration.ofSeconds(5)).untilTrue(cancelled);

        chatModel.enqueueStream(Flux.just(text("이어지는 답변")));
        assertThat(names(chat("이어지는 질문"))).endsWith("done");

        assertThat(memoryRows("USER")).isEqualTo(2);
        assertThat(memoryRows("ASSISTANT")).isEqualTo(1);
    }

    @Test
    void upstreamErrorEndsWithErrorEvent() throws Exception {
        chatModel.enqueueStream(Flux.just(text("중간까지"))
                .concatWith(Flux.error(new IllegalStateException("upstream secret"))));

        HttpResponse<Stream<String>> response = post("질문", BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        List<SseEvent> events;
        try (var lines = response.body()) {
            events = parse(lines.toList());
        }

        assertThat(names(events)).containsExactly("meta", "sources", "delta", "error");
        assertThat(events.getLast().data())
                .contains("\"code\":\"UPSTREAM\"")
                .doesNotContain("upstream secret");
    }

    @Test
    void inputRejectedByTheDatabaseIsNotLogged(CapturedOutput output) throws Exception {
        String marker = "DB-REJECTED-INPUT-MARKER";

        assertThat(names(chat(marker + " \\u0000 뒤"))).endsWith("error");

        assertThat(output.getOut() + output.getErr()).doesNotContain(marker);
    }

    private record SseEvent(String name, String data) {}

    private List<SseEvent> chat(String message) throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = post(message, BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var lines = response.body()) {
            return parse(lines.toList());
        }
    }

    private <T> HttpResponse<T> post(String message, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        String body = "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conversationId, message);
        return http.send(
                HttpRequest.newBuilder(uri("/api/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                handler);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static List<SseEvent> parse(List<String> lines) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        for (String line : lines) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length());
            } else if (line.startsWith("data:")) {
                events.add(new SseEvent(name, line.substring("data:".length())));
            }
        }
        return events;
    }

    private static List<String> names(List<SseEvent> events) {
        return events.stream().map(SseEvent::name).toList();
    }

    private static List<String> textsOf(List<Message> messages, MessageType type) {
        return messages.stream().filter(m -> m.getMessageType() == type).map(Message::getText).toList();
    }

    private long memoryRows(String type) {
        return jdbc.queryForObject(
                "select count(*) from spring_ai_chat_memory where conversation_id = ? and type = ?",
                Long.class,
                conversationId.toString(),
                type);
    }
}
