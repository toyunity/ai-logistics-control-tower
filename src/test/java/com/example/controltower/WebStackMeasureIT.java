package com.example.controltower;

import static com.example.controltower.support.FakeChatModel.finish;
import static com.example.controltower.support.FakeChatModel.text;

import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.FakeChatModel;
import com.example.controltower.support.TestInfraConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

/**
 * 웹 스택 비교용 측정이다. 단언 없이 숫자만 출력하고, MEASURE_WEB_STACK=1일 때만 돈다.
 * 클라이언트도 같은 JVM이라 전체 스레드 수 대신 서버 쪽 이름 접두사로 센다. 결과는 docs/decisions.md에 옮긴다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestInfraConfig.class, FakeAiConfig.class})
@EnabledIfEnvironmentVariable(named = "MEASURE_WEB_STACK", matches = "1")
class WebStackMeasureIT {

    private static final int STREAMS = 200;
    private static final int REQUESTS = 1000;
    private static final int CONCURRENCY = 50;
    private static final List<String> SERVER_PREFIXES =
            List.of("http-nio-", "tomcat-handler-", "reactor-http-nio-", "boundedElastic-", "parallel-");

    @LocalServerPort int port;
    @Autowired FakeChatModel model;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static Map<String, Integer> serverThreads() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            for (String prefix : SERVER_PREFIXES) {
                if (t.getName().startsWith(prefix)) {
                    counts.merge(prefix, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void concurrentSseStreams() throws Exception {
        model.reset();
        for (int i = 0; i < STREAMS; i++) {
            // 약 10초 동안 열려 있는 답변이다.
            model.enqueueStream(Flux.interval(Duration.ofMillis(200)).take(50).map(n -> text("가 "))
                    .concatWith(Flux.just(finish("end_turn"))));
        }
        Map<String, Integer> before = serverThreads();
        long started = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> streams = new ArrayList<>();
        for (int i = 0; i < STREAMS; i++) {
            streams.add(http.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"측정\"}"))
                            .build(),
                    BodyHandlers.ofString()));
            Thread.sleep(10); // 한꺼번에 연결하면 macOS 수락 대기열(128)을 넘어 연결이 리셋된다
        }
        Map<String, Integer> peak = before;
        CompletableFuture<Void> all = CompletableFuture.allOf(streams.toArray(CompletableFuture[]::new)).exceptionally(e -> null);
        while (!all.isDone()) {
            Map<String, Integer> now = serverThreads();
            if (total(now) > total(peak)) {
                peak = now;
            }
            Thread.sleep(100);
        }
        long done = streams.stream()
                .filter(f -> !f.isCompletedExceptionally() && f.join().body().contains("event:done"))
                .count();
        System.out.printf("MEASURE sse streams=%d done=%d elapsedMs=%d threadsBefore=%s peak=%s%n",
                STREAMS, done, Duration.ofNanos(System.nanoTime() - started).toMillis(), before, peak);
    }

    @Test
    void plantStatusUnderLoad() throws Exception {
        Semaphore slots = new Semaphore(CONCURRENCY);
        AtomicInteger ok = new AtomicInteger();
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        Map<String, Integer> before = serverThreads();
        long started = System.nanoTime();
        for (int i = 0; i < REQUESTS; i++) {
            slots.acquire();
            calls.add(http.sendAsync(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/plant/status")).build(),
                            BodyHandlers.discarding())
                    .thenAccept(r -> {
                        if (r.statusCode() == 200) {
                            ok.incrementAndGet();
                        }
                    })
                    .whenComplete((v, e) -> slots.release()));
        }
        Map<String, Integer> peak = serverThreads();
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("MEASURE plant requests=%d concurrency=%d ok=%d elapsedMs=%d rps=%.0f threadsBefore=%s atLastSubmit=%s%n",
                REQUESTS, CONCURRENCY, ok.get(), elapsed, REQUESTS * 1000.0 / elapsed, before, peak);
    }
}
