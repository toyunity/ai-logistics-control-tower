package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.controltower.adapter.out.knowledge.VectorKnowledgeSearch;
import com.example.controltower.application.service.SearchKnowledgeService;
import com.example.controltower.config.ControlTowerProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(KnowledgeController.class)
@Import({VectorKnowledgeSearch.class, SearchKnowledgeService.class}) // 문서를 응답으로 옮기는 매핑까지 함께 검증한다
@EnableConfigurationProperties(ControlTowerProperties.class) // 슬라이스 테스트에는 @ConfigurationPropertiesScan이 적용되지 않는다
@ExtendWith(OutputCaptureExtension.class)
class KnowledgeControllerTest {

    @Autowired WebTestClient client;
    @MockitoBean VectorStore vectorStore;

    private WebTestClient.ResponseSpec search(String q) {
        return client.get().uri(u -> u.path("/api/knowledge/search").queryParam("q", q).build()).exchange();
    }

    @Test
    void knowledgeSearchEndpoint() {
        Document doc = Document.builder()
                .id("cv-alarm-101")
                .text("제목: 알람 101\n내용: 하류 막힘")
                .metadata(Map.of("title", "알람 101", "body", "하류 막힘"))
                .score(0.87)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        search("알람 101")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("cv-alarm-101")
                .jsonPath("$[0].title").isEqualTo("알람 101")
                .jsonPath("$[0].body").isEqualTo("하류 막힘")
                .jsonPath("$[0].score").isEqualTo(0.87);
    }

    // pgvector 검색은 블로킹 JDBC다.
    @Test
    void searchRunsOffTheRequestThread() {
        AtomicReference<String> thread = new AtomicReference<>();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            return List.of();
        });

        search("알람").expectStatus().isOk();

        assertThat(thread.get()).startsWith("boundedElastic-");
    }

    @Test
    void blankQueryReturns400() {
        search(" ").expectStatus().isBadRequest();
    }

    @Test
    void tooLongQueryReturns400() {
        search("a".repeat(501)).expectStatus().isBadRequest();
    }

    @Test
    void storeFailure_500WithFixedBodyAndNoInputInLogs(CapturedOutput output) {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("SECRET-SQL-VALUE"));

        search("QUERY-MARKER")
                .expectStatus().isEqualTo(500)
                .expectBody(String.class)
                .value(b -> assertThat(b).doesNotContain("SECRET-SQL-VALUE").doesNotContain("QUERY-MARKER"));

        assertThat(output.getOut() + output.getErr()).doesNotContain("SECRET-SQL-VALUE").doesNotContain("QUERY-MARKER");
    }

    @Test
    void rejectedQueryIsNotLogged(CapturedOutput output) {
        search("QUERY-MARKER" + "a".repeat(501)).expectStatus().isBadRequest();

        assertThat(output.getOut() + output.getErr()).doesNotContain("QUERY-MARKER");
    }
}
