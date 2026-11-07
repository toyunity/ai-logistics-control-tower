package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 구현은 {@link #AMR_01}, {@link #AMR_02}로 시작한다. */
abstract class RobotFleetContract {

    static final RobotStatus AMR_01 = new RobotStatus("AMR-01", RunState.RUNNING, 82, 1, 4);
    static final RobotStatus AMR_02 = new RobotStatus("AMR-02", RunState.DEGRADED, 25, 2, 4);

    abstract RobotFleet subject();

    abstract void goDown();

    abstract void loseSendResponses();

    abstract List<UUID> sentCommandIds();

    private int queued(String id) {
        return subject().all().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow().queuedTasks();
    }

    @Test
    void all_readsTheFleet() {
        assertThat(subject().all()).containsExactly(AMR_01, AMR_02);
    }

    @Test
    void reassign_movesTheQueue() {
        subject().reassign(UUID.randomUUID(), "AMR-02", "AMR-01");

        assertThat(queued("AMR-01")).isEqualTo(3);
        assertThat(queued("AMR-02")).isZero();
    }

    @Test
    void commandIdIsSentOnWire() {
        UUID commandId = UUID.randomUUID();

        subject().reassign(commandId, "AMR-02", "AMR-01");

        assertThat(sentCommandIds()).containsExactly(commandId);
    }

    @Test
    void unknownRobot_isAGatewayFailure() {
        assertPlainGatewayFailure(() -> subject().reassign(UUID.randomUUID(), "AMR-09", "AMR-01"));
        assertPlainGatewayFailure(() -> subject().reassign(UUID.randomUUID(), "AMR-01", "AMR-09"));
    }

    @Test
    void down_readsAndSendsAreGatewayFailures() {
        goDown();

        assertPlainGatewayFailure(() -> subject().all());
        assertPlainGatewayFailure(() -> subject().reassign(UUID.randomUUID(), "AMR-02", "AMR-01"));
    }

    @Test
    void lostSendResponse_isOutcomeUnknown_thoughTheCommandApplied() {
        loseSendResponses();

        assertThatThrownBy(() -> subject().reassign(UUID.randomUUID(), "AMR-02", "AMR-01"))
                .isExactlyInstanceOf(CommandOutcomeUnknownException.class);

        assertThat(queued("AMR-01")).isEqualTo(3);
    }
}
