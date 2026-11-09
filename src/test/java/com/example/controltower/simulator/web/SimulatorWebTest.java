package com.example.controltower.simulator.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(
        controllers = {
            VendorAController.class,
            VendorBController.class,
            FleetController.class,
            SafetyController.class,
            SimControlController.class
        },
        properties = "controltower.simulator.enabled=true")
class SimulatorWebTest {

    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");

    @TestConfiguration
    static class Simulator {
        @Bean
        PlantSimulator plantSimulator() {
            return new PlantSimulator(42L, START);
        }
    }

    @Autowired WebTestClient client;
    @Autowired PlantSimulator sim;

    @BeforeEach
    void restoreInitialState() {
        sim.reset();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private WebTestClient.ResponseSpec postJson(String path, String body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec get(String path) {
        return client.get().uri(path).exchange();
    }

    private WebTestClient.ResponseSpec post(String path) {
        return client.post().uri(path).exchange();
    }

    private void jam() {
        sim.start("chute-jam");
        Instant t = START;
        for (int i = 0; i < 20; i++) {
            sim.tick(t = t.plusSeconds(1));
        }
    }

    private SimSnapshot.Plc cv3() {
        return sim.snapshot().conveyors().stream().filter(c -> c.id().equals("CV-3")).findFirst().orElseThrow();
    }

    private int jobs(String robot) {
        return sim.snapshot().robots().stream().filter(r -> r.name().equals(robot)).findFirst().orElseThrow().jobs();
    }

    @Test
    void vendorA_readsRegistersOnly() {
        get("/sim/vendor-a/plc/CV-3")
                .expectStatus().isOk()
                .expectBody().json("{\"registers\":[1,0,1]}", JsonCompareMode.STRICT);
    }

    @Test
    void vendorA_stoppedConveyorReportsItsAlarm() {
        jam();

        get("/sim/vendor-a/plc/CV-3")
                .expectBody().json("{\"registers\":[0,101,1]}", JsonCompareMode.STRICT);
    }

    @Test
    void vendorA_unknownPlc_is404() {
        get("/sim/vendor-a/plc/SR-1").expectStatus().isNotFound();
        postJson("/sim/vendor-a/plc/CV-9/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":1}")
                .expectStatus().isNotFound();
    }

    @Test
    void vendorA_startOp_restarts() {
        sim.start("worker-in-zone");

        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":1}")
                .expectStatus().isOk();

        assertThat(cv3().run()).isEqualTo(1);
    }

    @Test
    void vendorA_refusedDuringHold_is409() {
        jam();

        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":1}")
                .expectStatus().isEqualTo(409);

        assertThat(cv3().run()).isZero();
    }

    @Test
    void vendorA_otherOp_is400() {
        sim.start("worker-in-zone");

        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":2}")
                .expectStatus().isBadRequest();

        assertThat(cv3().run()).isZero();
    }

    @Test
    void vendorA_badBodies_are400_withoutEcho() {
        postJson("/sim/vendor-a/plc/CV-3/command", "{\"op\":1}").expectStatus().isBadRequest();
        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\"}").expectStatus().isBadRequest();
        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"secret-input\",\"op\":1}")
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(b -> assertThat(b).doesNotContain("secret-input"));
        postJson("/sim/vendor-a/plc/CV-3/command", "not json secret-input")
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(b -> assertThat(b).doesNotContain("secret-input"));
    }

    @Test
    void vendorB_readsSorter() {
        get("/sim/vendor-b/sorters/SR-1")
                .expectStatus().isOk()
                .expectBody().json("""
                        {"opStatus":"ACTV",
                         "lanes":[
                          {"laneNo":1,"grp":"A","fill":30,"cap":40,"rate":10,"st":"OK"},
                          {"laneNo":2,"grp":"A","fill":30,"cap":40,"rate":10,"st":"OK"},
                          {"laneNo":3,"grp":"A","fill":30,"cap":40,"rate":20,"st":"OK"},
                          {"laneNo":4,"grp":"A","fill":30,"cap":40,"rate":20,"st":"OK"},
                          {"laneNo":5,"grp":"B","fill":30,"cap":40,"rate":10,"st":"OK"},
                          {"laneNo":6,"grp":"B","fill":30,"cap":40,"rate":10,"st":"OK"}],
                         "reroutes":[]}""", JsonCompareMode.STRICT);
    }

    @Test
    void vendorB_rerouteShowsUpAndClearsHold() {
        jam();
        get("/sim/vendor-b/sorters/SR-1")
                .expectBody().jsonPath("$.opStatus").isEqualTo("HOLD")
                .jsonPath("$.lanes[2].st").isEqualTo("FULL");

        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":4}")
                .expectStatus().isOk();

        get("/sim/vendor-b/sorters/SR-1")
                .expectBody().jsonPath("$.opStatus").isEqualTo("ACTV")
                .jsonPath("$.reroutes[0].src").isEqualTo(3)
                .jsonPath("$.reroutes[0].dst").isEqualTo(4);
    }

    @Test
    void vendorB_refusedReroute_is409_unknownSorter_is404_badBody_is400() {
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":3}")
                .expectStatus().isEqualTo(409);
        get("/sim/vendor-b/sorters/SR-2").expectStatus().isNotFound();
        postJson("/sim/vendor-b/sorters/SR-2/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":4}")
                .expectStatus().isNotFound();
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"src\":3,\"dst\":4}").expectStatus().isBadRequest();
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"dst\":4}")
                .expectStatus().isBadRequest();
    }

    @Test
    void vendorB_unknownLane_is404_chainedReroute_is409() {
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":9}")
                .expectStatus().isNotFound();
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":0,\"dst\":4}")
                .expectStatus().isNotFound();

        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":4}")
                .expectStatus().isOk();
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":4,\"dst\":5}")
                .expectStatus().isEqualTo(409);
    }

    @Test
    void fleet_readsRobots_socAsFraction() {
        sim.start("robot-battery");

        get("/sim/fleet/robots")
                .expectStatus().isOk()
                .expectBody().json("""
                        [{"name":"AMR-01","mode":"AUTO","soc":0.82,"jobs":2,"maxJobs":4},
                         {"name":"AMR-02","mode":"CHARGING","soc":0.25,"jobs":2,"maxJobs":4},
                         {"name":"AMR-03","mode":"CHARGING","soc":0.25,"jobs":2,"maxJobs":4},
                         {"name":"AMR-04","mode":"AUTO","soc":0.9,"jobs":2,"maxJobs":4}]""", JsonCompareMode.STRICT);
    }

    @Test
    void fleet_reassign_movesJobs() {
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-02\",\"to\":\"AMR-01\"}")
                .expectStatus().isOk();

        assertThat(jobs("AMR-01")).isEqualTo(4);
        assertThat(jobs("AMR-02")).isZero();
    }

    @Test
    void fleet_unknown404_refused409_badBody400() {
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-09\",\"to\":\"AMR-01\"}")
                .expectStatus().isNotFound();
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-01\",\"to\":\"AMR-09\"}")
                .expectStatus().isNotFound();
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-01\",\"to\":\"AMR-01\"}")
                .expectStatus().isEqualTo(409);
        postJson("/sim/fleet/reassign", "{\"from\":\"AMR-02\",\"to\":\"AMR-01\"}").expectStatus().isBadRequest();
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"to\":\"AMR-01\"}")
                .expectStatus().isBadRequest();
    }

    @Test
    void safety_readsZone_tsIsEpochMillisOfLastTick() {
        Instant tick = START.plusSeconds(5);
        sim.tick(tick);
        sim.start("worker-in-zone");

        get("/sim/safety/zones/Z1")
                .expectStatus().isOk()
                .expectBody().json(
                        "{\"estop\":0,\"presence\":1,\"ts\":" + tick.toEpochMilli() + "}", JsonCompareMode.STRICT);
    }

    @Test
    void safety_unknownZone_is404() {
        get("/sim/safety/zones/Z9").expectStatus().isNotFound();
    }

    @Test
    void safety_offline_is503() {
        sim.start("safety-offline");

        get("/sim/safety/zones/Z1").expectStatus().isEqualTo(503);
    }

    @Test
    void scenario_startRepeatConflictUnknown() {
        post("/sim/scenarios/chute-jam").expectStatus().isOk();
        post("/sim/scenarios/chute-jam").expectStatus().isOk();
        post("/sim/scenarios/robot-battery").expectStatus().isEqualTo(409);
        post("/sim/scenarios/no-such").expectStatus().isNotFound();
    }

    @Test
    void reset_restoresTheLine() {
        post("/sim/scenarios/worker-in-zone").expectStatus().isOk();

        post("/sim/reset").expectStatus().isOk();

        assertThat(cv3().run()).isEqualTo(1);
        post("/sim/scenarios/robot-battery").expectStatus().isOk();
    }
}
