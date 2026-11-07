package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.adapter.out.equipment.EquipmentStub.Reply;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.RunState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VendorAConveyorsTest extends ConveyorsContract {

    private static final String CV_3 = "/sim/vendor-a/plc/CV-3";

    private final EquipmentStub stub = new EquipmentStub();
    private final VendorAConveyors adapter = new VendorAConveyors(stub.http());

    @BeforeEach
    void startRunning() {
        for (String id : List.of("CV-1", "CV-2", "CV-3")) {
            registers(id, "[1,0,1]");
            stub.on("POST", "/sim/vendor-a/plc/" + id + "/command", body -> {
                if (EquipmentStub.intField(body, "op") != 1) {
                    return new Reply(400, "");
                }
                registers(id, "[1,0,1]");
                return Reply.ok("");
            });
        }
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private void registers(String id, String registers) {
        stub.reply("GET", "/sim/vendor-a/plc/" + id, 200, "{\"registers\":" + registers + "}");
    }

    @Override
    Conveyors subject() {
        return adapter;
    }

    @Override
    void givenStoppedByDownstreamBlock(String id) {
        registers(id, "[0,101,1]");
    }

    @Override
    void goDown() {
        stub.goDown();
    }

    @Override
    void loseSendResponses() {
        stub.loseSendResponses();
    }

    @Override
    List<UUID> sentCommandIds() {
        return stub.sentIds("cmdId");
    }

    @ParameterizedTest
    @CsvSource({"0,NONE", "101,DOWNSTREAM_BLOCKED", "205,MECHANICAL", "999,UNKNOWN"})
    void alarmDrivesTheFaultOnly_stopRemainsStopped(int alarm, FaultClass fault) {
        registers("CV-3", "[0," + alarm + ",1]");

        assertThat(adapter.get("CV-3")).isEqualTo(new ConveyorStatus("CV-3", "Z1", RunState.STOPPED, fault));
    }

    @ParameterizedTest
    @CsvSource({"1,RUNNING", "0,STOPPED", "7,UNKNOWN"})
    void runRegister(int run, RunState state) {
        registers("CV-3", "[" + run + ",0,1]");

        assertThat(adapter.get("CV-3").state()).isEqualTo(state);
    }

    @ParameterizedTest
    @CsvSource({"1,Z1", "2,Z2", "3,Z3", "9,UNKNOWN"})
    void zoneCode(int code, String zone) {
        registers("CV-3", "[1,0," + code + "]");

        assertThat(adapter.get("CV-3").zone()).isEqualTo(zone);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"registers\":[1,0]}",
        "{\"registers\":[1,0,1,0]}",
        "{\"registers\":[1,null,1]}",
        "{\"registers\":[1.5,0,1]}",
        "{\"registers\":null}",
        "{}",
        "not json",
        "null",
        ""
    })
    void malformedRead_isAGatewayFailure(String body) {
        stub.reply("GET", CV_3, 200, body);

        assertPlainGatewayFailure(() -> adapter.get("CV-3"));
    }

    @Test
    void readTimeout_isAGatewayFailure() {
        stub.hang("GET", CV_3);

        assertPlainGatewayFailure(() -> adapter.get("CV-3"));
    }

    @Test
    void oneUnreadableConveyor_failsTheWholeList() {
        stub.reply("GET", CV_3, 503, "");

        assertPlainGatewayFailure(adapter::all);
    }

    @Test
    void restartSendsTheStartOp() {
        adapter.restart(UUID.randomUUID(), "CV-3");

        assertThat(EquipmentStub.intField(stub.sent().getFirst(), "op")).isEqualTo(1);
    }

    @Test
    void idIsEncodedIntoThePath_cannotReachAnotherRoute() {
        stub.reply("GET", "/sim/reset", 200, "{\"registers\":[1,0,1]}");

        assertPlainGatewayFailure(() -> adapter.get("../../reset"));
    }
}
