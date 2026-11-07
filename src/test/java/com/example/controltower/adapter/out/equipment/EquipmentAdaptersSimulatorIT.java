package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SorterStatus;
import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 스텁이 아닌 실제 시뮬레이터 응답을 번역한다. base-url을 비워 호출 시점의 포트 해석까지 함께 본다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "controltower.simulator.enabled=true")
@Import({TestInfraConfig.class, FakeAiConfig.class})
class EquipmentAdaptersSimulatorIT {

    @Autowired PlantSimulator sim;
    @Autowired Conveyors conveyors;
    @Autowired Sorters sorters;
    @Autowired RobotFleet fleet;
    @Autowired SafetySignals safety;

    @BeforeEach
    @AfterEach
    void restore() {
        sim.reset();
    }

    @Test
    void readsTheInitialPlantThroughEveryAdapter() {
        assertThat(conveyors.all()).hasSize(3).allMatch(c -> c.state() == RunState.RUNNING);
        assertThat(sorters.get().chutes()).hasSize(6);
        assertThat(fleet.all()).hasSize(4);
        assertThat(safety.read("Z1").zone()).isEqualTo("Z1");
    }

    @Test
    void chuteJam_holdStaysRunningAndBlocked_restartRefused_divertVisibleAtOnce() {
        assertThat(sim.start("chute-jam")).isTrue();

        await().atMost(Duration.ofSeconds(30)).until(() -> sorters.get().blocked());

        SorterStatus held = sorters.get();
        assertThat(held.state()).isEqualTo(RunState.RUNNING);
        assertThat(held.blocked()).isTrue();
        assertPlainGatewayFailure(() -> conveyors.restart(UUID.randomUUID(), "CV-3"));

        sorters.divert(UUID.randomUUID(), "CH-03", "CH-04");

        assertThat(sorters.get().diversions()).containsEntry("CH-03", "CH-04");
    }

    @Test
    void reassign_movesRealJobs() {
        fleet.reassign(UUID.randomUUID(), "AMR-02", "AMR-01");

        assertThat(fleet.all())
                .filteredOn(r -> r.id().equals("AMR-02"))
                .singleElement()
                .matches(r -> r.queuedTasks() == 0);
    }
}
