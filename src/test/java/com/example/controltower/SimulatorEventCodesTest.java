package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.service.IngestEventService;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimEvent;
import com.example.controltower.support.InMemoryEventLog;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 수신부는 형식에 맞지 않는 코드를 거절하고, 시뮬레이터 전송은 거절된 이벤트에서 멈춘다. 한 건만 어긋나도 뒤 이벤트가 전부 밀린다. */
class SimulatorEventCodesTest {

    private static final List<String> SCENARIOS =
            List.of("chute-jam", "robot-battery", "scanner-degraded", "worker-in-zone", "safety-offline");

    private final PlantSimulator sim = new PlantSimulator(7L, Instant.parse("2026-11-07T00:00:00Z"));
    private final IngestEventService ingest =
            new IngestEventService(new InMemoryEventLog(), LineTopology.DEFAULT, () -> {});
    private final Set<String> seen = new HashSet<>();
    private Instant now = Instant.parse("2026-11-07T00:00:00Z");

    private void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            sim.tick(now = now.plusSeconds(1));
        }
    }

    private void ingestDrained() {
        for (SimEvent e : sim.drainEvents()) {
            ingest.ingest(new EquipmentEvent(e.eventId(), e.equipmentId(), e.code(), e.value(), e.occurredAt()));
            seen.add(e.code());
        }
    }

    @Test
    void everyEventTheSimulatorEmits_passesIngestValidation() {
        sim.announceScanner();
        for (String scenario : SCENARIOS) {
            assertThat(sim.start(scenario)).isTrue();
            run(10);
            sim.reset();
            ingestDrained();
        }

        // 조치까지 해야 나오는 코드가 있다.
        assertThat(sim.start("chute-jam")).isTrue();
        run(10);
        assertThat(sim.divert(UUID.randomUUID(), 3, 4)).isTrue();
        run(45);
        ingestDrained();
        assertThat(sim.restart(UUID.randomUUID(), "CV-3")).isTrue();
        sim.reset();
        ingestDrained();

        assertThat(seen).containsExactlyInAnyOrder(
                "CHUTE_FULL", "CHUTE_CLEARED",
                "LANE_BLOCKED", "LANE_CLEARED", "DIVERT_RELEASED",
                "STOPPED", "RESTARTED",
                "BATTERY_LOW", "BATTERY_OK",
                "READ_DEGRADED", "READ_NORMAL");
    }
}
