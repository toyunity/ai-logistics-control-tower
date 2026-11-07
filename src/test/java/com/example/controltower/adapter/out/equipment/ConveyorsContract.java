package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.RunState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 구현은 CV-1~3이 Z1에서 고장 없이 도는 상태로 시작한다. */
abstract class ConveyorsContract {

    abstract Conveyors subject();

    abstract void givenStoppedByDownstreamBlock(String id);

    abstract void goDown();

    abstract void loseSendResponses();

    abstract List<UUID> sentCommandIds();

    private static ConveyorStatus running(String id) {
        return new ConveyorStatus(id, "Z1", RunState.RUNNING, FaultClass.NONE);
    }

    @Test
    void get_readsOneConveyor() {
        assertThat(subject().get("CV-2")).isEqualTo(running("CV-2"));
    }

    @Test
    void all_readsTheWholeLineInOrder() {
        assertThat(subject().all()).containsExactly(running("CV-1"), running("CV-2"), running("CV-3"));
    }

    @Test
    void get_reportsAStoppedConveyorsFault() {
        givenStoppedByDownstreamBlock("CV-3");

        assertThat(subject().get("CV-3"))
                .isEqualTo(new ConveyorStatus("CV-3", "Z1", RunState.STOPPED, FaultClass.DOWNSTREAM_BLOCKED));
    }

    @Test
    void restart_bringsItBackAndClearsTheFault() {
        givenStoppedByDownstreamBlock("CV-3");

        subject().restart(UUID.randomUUID(), "CV-3");

        assertThat(subject().get("CV-3")).isEqualTo(running("CV-3"));
    }

    @Test
    void commandIdIsSentOnWire() {
        UUID commandId = UUID.randomUUID();

        subject().restart(commandId, "CV-3");

        assertThat(sentCommandIds()).containsExactly(commandId);
    }

    @Test
    void unknownConveyor_isAGatewayFailure() {
        assertPlainGatewayFailure(() -> subject().get("CV-9"));
        assertPlainGatewayFailure(() -> subject().restart(UUID.randomUUID(), "CV-9"));
    }

    @Test
    void down_readsAndSendsAreGatewayFailures() {
        goDown();

        assertPlainGatewayFailure(() -> subject().get("CV-1"));
        assertPlainGatewayFailure(() -> subject().all());
        assertPlainGatewayFailure(() -> subject().restart(UUID.randomUUID(), "CV-1"));
    }

    @Test
    void lostSendResponse_isOutcomeUnknown_thoughTheCommandApplied() {
        givenStoppedByDownstreamBlock("CV-3");
        loseSendResponses();

        assertThatThrownBy(() -> subject().restart(UUID.randomUUID(), "CV-3"))
                .isExactlyInstanceOf(CommandOutcomeUnknownException.class);

        assertThat(subject().get("CV-3").state()).isEqualTo(RunState.RUNNING);
    }
}
