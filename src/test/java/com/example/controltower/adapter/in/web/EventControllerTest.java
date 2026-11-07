package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(EventController.class)
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
        IngestEvent ingestEvent(InMemoryEventLog log) {
            return new IngestEventService(log, LineTopology.DEFAULT, () -> {});
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired InMemoryEventLog log;

    @AfterEach
    void restore() {
        log.setFailing(false);
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(body));
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
            send(JSON.writeValueAsString(e)).andExpect(status().isOk());
        }
    }

    @Test
    void duplicate_200() throws Exception {
        String body = event(UUID.randomUUID().toString(), "CV-3", "STOPPED");

        send(body).andExpect(status().isOk());
        send(body).andExpect(status().isOk());
    }

    @Test
    void malformedEventId_400() throws Exception {
        send(event("a b", "CV-3", "STOPPED")).andExpect(status().isBadRequest());
        send(event("x".repeat(65), "CV-3", "STOPPED")).andExpect(status().isBadRequest());
        send(event("", "CV-3", "STOPPED")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownEquipmentOrMalformedCode_400() throws Exception {
        send(event(UUID.randomUUID().toString(), "CV-9", "STOPPED")).andExpect(status().isBadRequest());
        send(event(UUID.randomUUID().toString(), "CV-3", "stopped now")).andExpect(status().isBadRequest());
    }

    @Test
    void missingFields_400() throws Exception {
        send("{\"eventId\":\"e-1\",\"equipmentId\":\"CV-3\",\"code\":\"STOPPED\"}").andExpect(status().isBadRequest());
        send("{\"eventId\":\"e-1\",\"code\":\"STOPPED\",\"occurredAt\":\"2026-11-07T00:00:00Z\"}")
                .andExpect(status().isBadRequest());
        send("{\"eventId\":").andExpect(status().isBadRequest());
    }

    @Test
    void rejectedEventIsNotLogged(CapturedOutput output) throws Exception {
        send(event("EVENT MARKER", "CV-3", "STOPPED")).andExpect(status().isBadRequest());
        send(event(UUID.randomUUID().toString(), "EQUIPMENT-MARKER", "STOPPED")).andExpect(status().isBadRequest());
        send(event(UUID.randomUUID().toString(), "CV-3", "CODE MARKER")).andExpect(status().isBadRequest());

        assertThat(output.getOut() + output.getErr()).doesNotContain("MARKER");
    }

    @Test
    void storeFailure_500WithFixedBody() throws Exception {
        log.setFailing(true);

        send(event(UUID.randomUUID().toString(), "CV-3", "STOPPED"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").isString());
    }
}
