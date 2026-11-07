package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.adapter.out.equipment.EquipmentStub.Reply;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.RunState;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VendorBSortersTest extends SortersContract {

    private static final String SR_1 = "/sim/vendor-b/sorters/SR-1";
    private static final String LANES = """
            [{"laneNo":3,"grp":"A","fill":60,"cap":40,"rate":20,"st":"OK"},
             {"laneNo":4,"grp":"A","fill":30,"cap":40,"rate":10,"st":"OK"}]""";

    private final EquipmentStub stub = new EquipmentStub();
    private final VendorBSorters adapter = new VendorBSorters(stub.http());
    private final List<String> reroutes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startRunning() {
        stub.on("GET", SR_1, body -> Reply.ok(sorter("ACTV", LANES, reroutes.toString())));
        stub.on("POST", SR_1 + "/reroutes", body -> {
            reroutes.add("{\"src\":" + EquipmentStub.intField(body, "src")
                    + ",\"dst\":" + EquipmentStub.intField(body, "dst") + "}");
            return Reply.ok("");
        });
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static String sorter(String opStatus, String lanes, String reroutes) {
        return "{\"opStatus\":\"" + opStatus + "\",\"lanes\":" + lanes + ",\"reroutes\":" + reroutes + "}";
    }

    @Override
    Sorters subject() {
        return adapter;
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
        return stub.sentIds("txn");
    }

    @ParameterizedTest
    @CsvSource({
        "ACTV,RUNNING,false",
        "HOLD,RUNNING,true", // 차단 중에도 우회는 가능해야 하므로 멈춤으로 번역하지 않는다
        "JAM,FAULT,true",
        "WAT,UNKNOWN,true"
    })
    void opStatus(String opStatus, RunState state, boolean blocked) {
        stub.reply("GET", SR_1, 200, sorter(opStatus, "[]", "[]"));

        assertThat(adapter.get().state()).isEqualTo(state);
        assertThat(adapter.get().blocked()).isEqualTo(blocked);
    }

    @ParameterizedTest
    @CsvSource({"OK,RUNNING,false", "FULL,RUNNING,true", "ERR,FAULT,false", "WAT,UNKNOWN,false"})
    void laneStatus(String st, RunState state, boolean full) {
        stub.reply("GET", SR_1, 200, sorter("ACTV",
                "[{\"laneNo\":6,\"grp\":\"B\",\"fill\":100,\"cap\":40,\"rate\":20,\"st\":\"" + st + "\"}]", "[]"));

        assertThat(adapter.get().chutes()).containsExactly(new ChuteStatus("CH-06", "B", state, 100, 20, 40, full));
    }

    @Test
    void reroutesBecomeDiversions() {
        stub.reply("GET", SR_1, 200, sorter("ACTV", "[]", "[{\"src\":3,\"dst\":4},{\"src\":1,\"dst\":12}]"));

        assertThat(adapter.get().diversions()).isEqualTo(Map.of("CH-03", "CH-04", "CH-01", "CH-12"));
    }

    @Test
    void divertSendsLaneNumbers() {
        adapter.divert(UUID.randomUUID(), "CH-03", "CH-04");

        String sent = stub.sent().getFirst();
        assertThat(EquipmentStub.intField(sent, "src")).isEqualTo(3);
        assertThat(EquipmentStub.intField(sent, "dst")).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"opStatus\":\"ACTV\",\"lanes\":[]}",
        "{\"opStatus\":null,\"lanes\":[],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[null],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[{\"laneNo\":3}],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[{\"grp\":\"A\",\"fill\":1,\"cap\":1,\"rate\":1,\"st\":\"OK\"}],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[{\"laneNo\":3,\"grp\":null,\"fill\":1,\"cap\":1,\"rate\":1,\"st\":\"OK\"}],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[{\"laneNo\":3,\"grp\":\"A\",\"fill\":1,\"cap\":-1,\"rate\":1,\"st\":\"OK\"}],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[{\"laneNo\":3,\"grp\":\"A\",\"fill\":101,\"cap\":1,\"rate\":1,\"st\":\"OK\"}],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[{\"laneNo\":3,\"grp\":\"A\",\"fill\":-1,\"cap\":1,\"rate\":1,\"st\":\"OK\"}],\"reroutes\":[]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[],\"reroutes\":[{\"src\":3}]}",
        "{\"opStatus\":\"ACTV\",\"lanes\":[],\"reroutes\":[{\"src\":3,\"dst\":4},{\"src\":3,\"dst\":5}]}",
        "not json"
    })
    void malformedRead_isAGatewayFailure(String body) {
        stub.reply("GET", SR_1, 200, body);

        assertPlainGatewayFailure(adapter::get);
    }

    @Test
    void readTimeout_isAGatewayFailure() {
        stub.hang("GET", SR_1);

        assertPlainGatewayFailure(adapter::get);
    }
}
