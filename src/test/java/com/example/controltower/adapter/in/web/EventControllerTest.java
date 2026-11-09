package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.port.in.IngestEvent;
import com.example.controltower.application.service.IngestEventService;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimEvent;
import com.example.controltower.support.InMemoryEventLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

@WebFluxTest(EventController.class)
@Import(EventControllerTest.Wiring.class)
@ExtendWith(OutputCaptureExtension.class)
class EventControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        InMemoryEventLog eventLog() {
            return new InMemoryEventLog();
        }

        @Bean
        SlowDetection slowDetection() {
            return new SlowDetection();
        }

        @Bean(destroyMethod = "shutdownNow")
        ExecutorService detectionExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        IngestEvent ingestEvent(InMemoryEventLog log, SlowDetection detection, ExecutorService detectionExecutor) {
            return new IngestEventService(log, LineTopology.DEFAULT, detection, detectionExecutor);
        }
    }

    /** 게이트웨이가 응답하지 않는 감지 주기를 흉내 낸다. hold가 있으면 풀릴 때까지 붙잡는다. */
    static class SlowDetection implements DetectAnomalies {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch hold;

        @Override
        public void detect() {
            CountDownLatch h = hold;
            if (h == null) {
                return;
            }
            entered.countDown();
            try {
                h.await(60, TimeUnit.SECONDS); // 테스트 제한(30초)보다 길게 붙잡아, 응답이 감지를 기다리면 실패하게 한다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebTestClient client;
    @Autowired InMemoryEventLog log;
    @Autowired SlowDetection detection;

    @AfterEach
    void restore() {
        log.setFailing(false);
    }

    private WebTestClient.ResponseSpec send(String body) {
        return client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static String event(String eventId, String equipmentId, String code) {
        return "{\"eventId\":\"" + eventId + "\",\"equipmentId\":\"" + equipmentId + "\",\"code\":\"" + code
                + "\",\"value\":3,\"occurredAt\":\"2026-11-07T00:00:00Z\"}";
    }

    /** 시뮬레이터는 거절된 이벤트에서 전송을 멈추므로 한 건이라도 4xx면 뒤 이벤트가 전부 밀린다. 실제 직렬화 형태로 보낸다. */
    @Test
    void everyEventShapeTheSimulatorEmits_isAccepted() throws Exception {
        PlantSimulator sim = new PlantSimulator(7L, Instant.parse("2026-11-07T00:00:00Z"));
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        List<SimEvent> emitted = new ArrayList<>();
        sim.announceScanner();
        for (String scenario : List.of("chute-jam", "robot-battery", "scanner-degraded", "worker-in-zone", "safety-offline")) {
            assertThat(sim.start(scenario)).isTrue();
            for (int i = 0; i < 10; i++) {
                sim.tick(now = now.plusSeconds(1));
            }
            sim.reset();
            emitted.addAll(sim.drainEvents());
        }
        assertThat(sim.start("chute-jam")).isTrue();
        for (int i = 0; i < 10; i++) {
            sim.tick(now = now.plusSeconds(1));
        }
        assertThat(sim.divert(UUID.randomUUID(), 3, 4)).isTrue();
        for (int i = 0; i < 45; i++) {
            sim.tick(now = now.plusSeconds(1));
        }
        assertThat(sim.restart(UUID.randomUUID(), "CV-3")).isTrue();
        sim.reset();
        emitted.addAll(sim.drainEvents());

        assertThat(emitted).extracting(SimEvent::code).contains("STOPPED", "RESTARTED", "DIVERT_RELEASED", "READ_NORMAL");
        for (SimEvent e : emitted) {
            send(JSON.writeValueAsString(e)).expectStatus().isOk();
        }
    }

    // 응답이 감지 주기를 기다리면 시뮬레이터 전송이 읽기 타임아웃에 걸려 큐가 밀린다. 타임아웃은 교착 감지용이다.
    @Test
    @Timeout(30)
    void respondsWhileDetectionIsStuckOnASlowGateway() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        detection.hold = hold;
        try {
            send(event(UUID.randomUUID().toString(), "CV-3", "STOPPED")).expectStatus().isOk();

            assertThat(detection.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(hold.getCount()).isEqualTo(1);
        } finally {
            detection.hold = null;
            hold.countDown();
        }
    }

    @Test
    void duplicate_200() throws Exception {
        String body = event(UUID.randomUUID().toString(), "CV-3", "STOPPED");

        send(body).expectStatus().isOk();
        send(body).expectStatus().isOk();
    }

    @Test
    void malformedEventId_400() throws Exception {
        send(event("a b", "CV-3", "STOPPED")).expectStatus().isBadRequest();
        send(event("x".repeat(65), "CV-3", "STOPPED")).expectStatus().isBadRequest();
        send(event("", "CV-3", "STOPPED")).expectStatus().isBadRequest();
    }

    @Test
    void unknownEquipmentOrMalformedCode_400() throws Exception {
        send(event(UUID.randomUUID().toString(), "CV-9", "STOPPED")).expectStatus().isBadRequest();
        send(event(UUID.randomUUID().toString(), "CV-3", "stopped now")).expectStatus().isBadRequest();
    }

    @Test
    void missingFields_400() throws Exception {
        send("{\"eventId\":\"e-1\",\"equipmentId\":\"CV-3\",\"code\":\"STOPPED\"}").expectStatus().isBadRequest();
        send("{\"eventId\":\"e-1\",\"code\":\"STOPPED\",\"occurredAt\":\"2026-11-07T00:00:00Z\"}")
                .expectStatus().isBadRequest();
        send("{\"eventId\":").expectStatus().isBadRequest();
    }

    @Test
    void rejectedEventIsNotLogged(CapturedOutput output) throws Exception {
        send(event("EVENT MARKER", "CV-3", "STOPPED")).expectStatus().isBadRequest();
        send(event(UUID.randomUUID().toString(), "EQUIPMENT-MARKER", "STOPPED")).expectStatus().isBadRequest();
        send(event(UUID.randomUUID().toString(), "CV-3", "CODE MARKER")).expectStatus().isBadRequest();

        assertThat(output.getOut() + output.getErr()).doesNotContain("MARKER");
    }

    @Test
    void storeFailure_500WithFixedBody() throws Exception {
        log.setFailing(true);

        send(event(UUID.randomUUID().toString(), "CV-3", "STOPPED"))
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.message").isNotEmpty();
    }
}
