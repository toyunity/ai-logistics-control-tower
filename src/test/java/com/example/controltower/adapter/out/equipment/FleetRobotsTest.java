package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.adapter.out.equipment.EquipmentStub.Reply;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FleetRobotsTest extends RobotFleetContract {

    private static final String ROBOTS = "/sim/fleet/robots";

    private final EquipmentStub stub = new EquipmentStub();
    private final FleetRobots adapter = new FleetRobots(stub.http());
    private final Map<String, Integer> jobs = new ConcurrentHashMap<>(Map.of("AMR-01", 1, "AMR-02", 2));

    @BeforeEach
    void start() {
        stub.on("GET", ROBOTS, body -> Reply.ok("[" + robot("AMR-01", "AUTO", "0.82", jobs.get("AMR-01")) + ","
                + robot("AMR-02", "CHARGING", "0.25", jobs.get("AMR-02")) + "]"));
        stub.on("POST", "/sim/fleet/reassign", body -> {
            String from = EquipmentStub.field(body, "from");
            String to = EquipmentStub.field(body, "to");
            if (!jobs.containsKey(from) || !jobs.containsKey(to)) {
                return new Reply(404, "");
            }
            jobs.put(to, jobs.get(to) + jobs.put(from, 0));
            return Reply.ok("");
        });
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static String robot(String name, String mode, String soc, int jobs) {
        return "{\"name\":\"" + name + "\",\"mode\":\"" + mode + "\",\"soc\":" + soc + ",\"jobs\":" + jobs
                + ",\"maxJobs\":4}";
    }

    @Override
    RobotFleet subject() {
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
        return stub.sentIds("cmdId");
    }

    @ParameterizedTest
    @CsvSource({"AUTO,RUNNING", "CHARGING,DEGRADED", "LOST,OFFLINE", "WAT,UNKNOWN"})
    void mode(String mode, RunState state) {
        stub.reply("GET", ROBOTS, 200, "[" + robot("AMR-01", mode, "0.5", 0) + "]");

        assertThat(adapter.all().getFirst().state()).isEqualTo(state);
    }

    @ParameterizedTest
    @CsvSource({"0.82,82", "0.29,29", "0.255,26", "1,100", "0,0"})
    void socIsARoundedPercent(String soc, int percent) {
        stub.reply("GET", ROBOTS, 200, "[" + robot("AMR-01", "AUTO", soc, 0) + "]");

        assertThat(adapter.all().getFirst().batteryPercent()).isEqualTo(percent);
    }

    @Test
    void jobsBecomeTheQueue() {
        stub.reply("GET", ROBOTS, 200, "[" + robot("AMR-01", "AUTO", "0.5", 3) + "]");

        assertThat(adapter.all()).containsExactly(new RobotStatus("AMR-01", RunState.RUNNING, 50, 3, 4));
    }

    @Test
    void reassignSendsFromAndTo() {
        adapter.reassign(UUID.randomUUID(), "AMR-02", "AMR-01");

        String sent = stub.sent().getFirst();
        assertThat(EquipmentStub.field(sent, "from")).isEqualTo("AMR-02");
        assertThat(EquipmentStub.field(sent, "to")).isEqualTo("AMR-01");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[null]",
        "[{\"name\":\"AMR-01\"}]",
        "[{\"mode\":\"AUTO\",\"soc\":0.5,\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":0.5,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":null,\"soc\":0.5,\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":5,\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":25,\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":42949673.5,\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":-0.1,\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":\"NaN\",\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":\"Infinity\",\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":\"0.82\",\"jobs\":0,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":0.5,\"jobs\":-1,\"maxJobs\":4}]",
        "[{\"name\":\"AMR-01\",\"mode\":\"AUTO\",\"soc\":0.5,\"jobs\":0,\"maxJobs\":-1}]",
        "{}",
        "not json"
    })
    void malformedRead_isAGatewayFailure(String body) {
        stub.reply("GET", ROBOTS, 200, body);

        assertPlainGatewayFailure(adapter::all);
    }

    @Test
    void readTimeout_isAGatewayFailure() {
        stub.hang("GET", ROBOTS);

        assertPlainGatewayFailure(adapter::all);
    }
}
