package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.out.SafetySignals;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafetyPlcSignalsTest extends SafetySignalsContract {

    private static final String Z1 = "/sim/safety/zones/Z1";

    private final EquipmentStub stub = new EquipmentStub();
    private final SafetyPlcSignals adapter = new SafetyPlcSignals(stub.http());

    @AfterEach
    void stop() {
        stub.close();
    }

    @Override
    SafetySignals subject() {
        return adapter;
    }

    @Override
    void givenSignal(String zone, boolean emergencyStop, boolean workerPresent, Instant measuredAt) {
        stub.reply("GET", "/sim/safety/zones/" + zone, 200,
                "{\"estop\":" + (emergencyStop ? 1 : 0) + ",\"presence\":" + (workerPresent ? 1 : 0)
                        + ",\"ts\":" + measuredAt.toEpochMilli() + "}");
    }

    @Override
    void goDown() {
        stub.goDown();
    }

    @Test
    void tsIsTheMeasuredTimeUnchanged() {
        stub.reply("GET", Z1, 200, "{\"estop\":0,\"presence\":0,\"ts\":1234567890123}");

        assertThat(adapter.read("Z1").measuredAt()).isEqualTo(Instant.ofEpochMilli(1234567890123L));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"estop\":1,\"estop\":0,\"presence\":0,\"ts\":1}",
        "{\"estop\":2,\"presence\":0,\"ts\":1}",
        "{\"estop\":0,\"presence\":-1,\"ts\":1}",
        "{\"estop\":true,\"presence\":0,\"ts\":1}",
        "{\"estop\":\"0\",\"presence\":0,\"ts\":1}",
        "{\"estop\":null,\"presence\":0,\"ts\":1}",
        "{\"presence\":0,\"ts\":1}",
        "{\"estop\":0,\"ts\":1}",
        "{\"estop\":0,\"presence\":0}",
        "{\"estop\":0,\"presence\":0,\"ts\":null}",
        "{\"estop\":0,\"presence\":0,\"ts\":\"yesterday\"}",
        "not json"
    })
    void malformedRead_isAGatewayFailure(String body) {
        stub.reply("GET", Z1, 200, body);

        assertPlainGatewayFailure(() -> adapter.read("Z1"));
    }

    @Test
    void offline503_isAGatewayFailure() {
        stub.reply("GET", Z1, 503, "");

        assertPlainGatewayFailure(() -> adapter.read("Z1"));
    }

    @Test
    void readTimeout_isAGatewayFailure() {
        stub.hang("GET", Z1);

        assertPlainGatewayFailure(() -> adapter.read("Z1"));
    }
}
