package com.example.controltower.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class SimulatorRunnerTest {

    private static final String TARGET_KEY = "controltower.simulator.event-target";

    private final PlantSimulator sim = new PlantSimulator(42L, Instant.parse("2026-11-07T00:00:00Z"));
    private final MockEnvironment env = new MockEnvironment();
    private final SimulatorRunner runner = new SimulatorRunner(sim, env);
    private final JsonMapper json = JsonMapper.builder().build();

    private final Queue<String> received = new ConcurrentLinkedQueue<>();
    private final Deque<Integer> statuses = new ArrayDeque<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/events", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Integer status = statuses.poll();
            exchange.sendResponseHeaders(status == null ? 200 : status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private List<String> receivedKeys() {
        return received.stream().map(this::key).toList();
    }

    private String key(String body) {
        JsonNode event = json.readTree(body);
        return event.get("equipmentId").asString() + ":" + event.get("code").asString();
    }

    @Test
    void firstTick_announcesScannerOnce_inTheGatewayFormat() {
        env.setProperty(TARGET_KEY, serverUrl());

        runner.tick();
        runner.tick();

        assertThat(received).hasSize(1);
        JsonNode event = json.readTree(received.peek());
        assertThat(event.propertyNames()).containsExactlyInAnyOrder("eventId", "equipmentId", "code", "value", "occurredAt");
        assertThat(event.get("equipmentId").asString()).isEqualTo("SC-1");
        assertThat(event.get("code").asString()).isEqualTo("READ_NORMAL");
        assertThat(event.get("value").asInt()).isEqualTo(1);
        assertThat(event.get("eventId").asString()).isNotBlank();
        assertThat(Instant.parse(event.get("occurredAt").asString())).isNotNull();
    }

    @Test
    void scenarioBeforeFirstTick_isNotOverwrittenByTheAnnouncement() {
        env.setProperty(TARGET_KEY, serverUrl());
        sim.start("scanner-degraded");

        runner.tick();

        assertThat(receivedKeys()).containsExactly("SC-1:READ_DEGRADED");
    }

    @Test
    void pushKeepsOrder() {
        env.setProperty(TARGET_KEY, serverUrl());
        sim.start("robot-battery");

        runner.tick();

        assertThat(receivedKeys()).containsExactly("AMR-02:BATTERY_LOW", "AMR-03:BATTERY_LOW", "SC-1:READ_NORMAL");
    }

    @Test
    void failedPush_stopsAtTheFailure_andResumesFromItNextTick() {
        env.setProperty(TARGET_KEY, serverUrl());
        sim.start("robot-battery");
        statuses.add(200);
        statuses.add(500);

        runner.tick();

        assertThat(receivedKeys()).containsExactly("AMR-02:BATTERY_LOW", "AMR-03:BATTERY_LOW");

        runner.tick();

        assertThat(receivedKeys()).containsExactly(
                "AMR-02:BATTERY_LOW", "AMR-03:BATTERY_LOW", "AMR-03:BATTERY_LOW", "SC-1:READ_NORMAL");
    }

    @Test
    void blankTarget_waitsForTheServerPort_thenUsesLoopback() {
        env.setProperty(TARGET_KEY, " ");

        runner.tick();
        assertThat(received).isEmpty();

        env.setProperty("local.server.port", String.valueOf(server.getAddress().getPort()));
        runner.tick();

        assertThat(receivedKeys()).containsExactly("SC-1:READ_NORMAL");
    }

    @Test
    void unreachableTarget_isSilentAboveDebug_andKeepsTheQueue(CapturedOutput output) {
        env.setProperty(TARGET_KEY, "http://127.0.0.1:1");

        runner.tick();

        assertThat(output).doesNotContain("WARN").doesNotContain("ERROR");

        env.setProperty(TARGET_KEY, serverUrl());
        runner.tick();
        assertThat(receivedKeys()).containsExactly("SC-1:READ_NORMAL");
    }

    @Test
    void tickAdvancesTheSimulatorEvenWithoutATarget() {
        sim.start("chute-jam");

        for (int i = 0; i < 2; i++) {
            runner.tick();
        }

        assertThat(sim.snapshot().opStatus()).isEqualTo("HOLD");
    }
}
