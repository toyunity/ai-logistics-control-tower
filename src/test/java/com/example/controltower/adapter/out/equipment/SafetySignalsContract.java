package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.domain.SafetyStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

abstract class SafetySignalsContract {

    private static final Instant MEASURED = Instant.parse("2026-11-07T01:02:03.456Z");

    abstract SafetySignals subject();

    abstract void givenSignal(String zone, boolean emergencyStop, boolean workerPresent, Instant measuredAt);

    abstract void goDown();

    @Test
    void read_keepsTheMeasuredTimeAndTheAskedZone() {
        givenSignal("Z1", false, true, MEASURED);
        givenSignal("Z2", true, false, MEASURED);

        assertThat(subject().read("Z1")).isEqualTo(new SafetyStatus("Z1", false, true, MEASURED));
        assertThat(subject().read("Z2")).isEqualTo(new SafetyStatus("Z2", true, false, MEASURED));
    }

    @Test
    void down_isAGatewayFailure() {
        goDown();

        assertPlainGatewayFailure(() -> subject().read("Z1"));
    }
}
