package com.example.controltower.adapter.out.equipment;

import com.example.controltower.adapter.out.equipment.EquipmentStub.Reply;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 분류기 게이트웨이 하나가 응답하지 않을 때 현황 조회 시간을 잰다. 실제 설정(application.yaml)의 타임아웃과 회로 값을 쓴다.
 * 단언 없이 숫자만 출력하고 MEASURE_BREAKER=1일 때만 돈다. 결과는 docs/decisions.md에 옮긴다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestInfraConfig.class, FakeAiConfig.class})
@EnabledIfEnvironmentVariable(named = "MEASURE_BREAKER", matches = "1")
class EquipmentBreakerMeasureIT {

    private static final EquipmentStub GATEWAY = new EquipmentStub();
    private static final String SORTER = "/sim/vendor-b/sorters/SR-1";

    static {
        for (String id : List.of("CV-1", "CV-2", "CV-3")) {
            GATEWAY.reply("GET", "/sim/vendor-a/plc/" + id, 200, "{\"registers\":[1,0,1]}");
        }
        GATEWAY.reply("GET", "/sim/fleet/robots", 200,
                "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":0.8,\"jobs\":1,\"maxJobs\":5}]");
        GATEWAY.hang("GET", SORTER);
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        registry.add("controltower.equipment.base-url", GATEWAY::baseUrl);
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @LocalServerPort int port;
    @Autowired CircuitBreakerRegistry breakers;

    private final HttpClient http = HttpClient.newHttpClient();

    private String status() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/plant/status")).build(),
                BodyHandlers.ofString()).body();
    }

    private static boolean sorterKnown(String body) {
        return body.contains("\"id\":\"SR-1\"") && !body.matches("(?s).*\"id\":\"SR-1\"[^}]*\"state\":\"UNKNOWN\".*");
    }

    @Test
    void statusReadTime_withTheSorterGatewayUnresponsive() throws Exception {
        for (int poll = 1; poll <= 8; poll++) {
            long started = System.nanoTime();
            status();
            long ms = (System.nanoTime() - started) / 1_000_000;
            System.out.printf("MEASURE poll=%d ms=%d sorterCircuit=%s failed=%d%n", poll, ms,
                    breakers.circuitBreaker("sorter").getState(),
                    breakers.circuitBreaker("sorter").getMetrics().getNumberOfFailedCalls());
        }
        GATEWAY.on("GET", SORTER, b -> Reply.ok("{\"opStatus\":\"ACTV\",\"lanes\":[],\"reroutes\":[]}"));
        long recovered = System.nanoTime();
        int polls = 0;
        String body;
        do {
            Thread.sleep(500);
            body = status();
            polls++;
        } while (!sorterKnown(body) && polls < 120);
        System.out.printf("MEASURE recovery: sorter known after ms=%d polls=%d circuit=%s%n",
                (System.nanoTime() - recovered) / 1_000_000, polls, breakers.circuitBreaker("sorter").getState());
    }
}
