package com.example.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.consult.support.TestInfraConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Calls the real Claude and OpenAI APIs, so it costs a little money and runs only with both keys set. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig.class)
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@Timeout(180)
class LiveApiIT {

    /** One reworded question per entry in faqs.json, avoiding the entry's own wording where possible. */
    private static final List<Map.Entry<String, String>> PARAPHRASES = List.of(
            Map.entry("구매한 물건을 돌려보내고 돈을 돌려받고 싶어요", "refund-policy"),
            Map.entry("사이즈가 안 맞아서 다른 걸로 바꾸고 싶은데 어떻게 하죠?", "exchange-policy"),
            Map.entry("오늘 주문하면 언제쯤 받을 수 있나요?", "shipping-time"),
            Map.entry("택배비가 따로 붙나요? 얼마부터 공짜인가요?", "shipping-fee"),
            Map.entry("방금 결제한 주문을 없던 걸로 하고 싶어요", "order-cancel"),
            Map.entry("카카오페이로 계산할 수 있어요?", "payment-methods"),
            Map.entry("할인권은 결제할 때 어디에서 적용하나요?", "coupon-usage"),
            Map.entry("모아둔 포인트가 언제 사라지나요?", "points-expiry"),
            Map.entry("구글 계정으로 새로 등록할 수 있나요?", "membership-signup"),
            Map.entry("로그인 암호가 기억나지 않아요", "password-reset"),
            Map.entry("제 택배가 지금 어디쯤 왔는지 보고 싶어요", "order-tracking"),
            Map.entry("사업자 증빙용 서류를 발급받고 싶어요", "receipt-issue"));

    private static final List<String> UNRELATED = List.of(
            "오늘 서울 날씨 어때요?",
            "파이썬으로 정렬 알고리즘 짜는 법 알려줘",
            "김치찌개 맛있게 끓이는 법이 궁금해요",
            "임진왜란은 몇 년에 일어났나요?",
            "화성까지 가는 데 얼마나 걸리나요?");

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort int port;
    @Autowired ChatMemory memory;
    @Autowired VectorStore vectorStore;
    @Autowired ConsultProperties props;

    @Test
    void realStreamCompletes() throws Exception {
        String body = chat(UUID.randomUUID(), "환불은 며칠 안에 가능한가요?");

        assertThat(body)
                .containsSubsequence("event:meta", "event:sources", "event:delta", "event:done")
                .contains("\"id\":\"refund-policy\"")
                .doesNotContain("event:error");
    }

    @Test
    void followUpUsesHistory() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(chat(id, "환불은 며칠 안에 가능한가요?")).contains("event:done");

        // RagConfig falls back to the raw query if the rewrite fails, so the raw query must not find the FAQ alone.
        String followUp = "그 기간이 지나면 어떻게 되나요?";
        assertThat(search(followUp, props.similarityThreshold()))
                .as("raw follow-up already finds refund-policy, so pick a question that proves the rewrite")
                .noneMatch(d -> d.getId().equals("refund-policy"));

        String body = chat(id, followUp);

        assertThat(body).contains("event:done", "\"id\":\"refund-policy\"").doesNotContain("event:error");
    }

    /** History ends in USER after a disconnect, or after a blank reply that memory dropped. */
    @Test
    void consecutiveUserMessagesAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        memory.add(id.toString(), new UserMessage("환불하고 싶어요"));

        String body = chat(id, "배송비는 얼마인가요?");

        assertThat(body).contains("event:done").doesNotContain("event:error");
    }

    @Test
    void retrievalHitRate() {
        System.out.printf("threshold=%.2f topK=%d, scores below ignore the threshold%n",
                props.similarityThreshold(), props.topK());
        System.out.println("question | expected | score | hit");

        int hits = 0;
        for (var c : PARAPHRASES) {
            String question = c.getKey();
            String expected = c.getValue();
            boolean hit = search(question, props.similarityThreshold()).stream()
                    .anyMatch(d -> d.getId().equals(expected));
            String score = search(question, SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL).stream()
                    .filter(d -> d.getId().equals(expected))
                    .map(d -> "%.3f".formatted(d.getScore()))
                    .findFirst()
                    .orElse("not in top-k");
            hits += hit ? 1 : 0;
            System.out.printf("%s | %s | %s | %s%n", question, expected, score, hit);
        }

        for (String question : UNRELATED) {
            int returned = search(question, props.similarityThreshold()).size();
            String score = search(question, SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL).stream()
                    .map(d -> "%.3f".formatted(d.getScore()))
                    .findFirst()
                    .orElse("-");
            System.out.printf("%s | (unrelated) | %s | returned=%d%n", question, score, returned);
        }

        double hitRate = (double) hits / PARAPHRASES.size();
        System.out.printf("hit@%d = %d/%d = %.3f%n", props.topK(), hits, PARAPHRASES.size(), hitRate);
        assertThat(hitRate).isGreaterThanOrEqualTo(0.9);
    }

    private List<Document> search(String query, double threshold) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(props.topK())
                .similarityThreshold(threshold)
                .build());
    }

    private String chat(UUID id, String message) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(id, message)))
                        .build(),
                BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
