package com.example.controltower.simulator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(
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

    @Autowired MockMvc mvc;
    @Autowired PlantSimulator sim;

    @BeforeEach
    void restoreInitialState() {
        sim.reset();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
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
    void vendorA_readsRegistersOnly() throws Exception {
        mvc.perform(get("/sim/vendor-a/plc/CV-3"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"registers\":[1,0,1]}", JsonCompareMode.STRICT));
    }

    @Test
    void vendorA_stoppedConveyorReportsItsAlarm() throws Exception {
        jam();

        mvc.perform(get("/sim/vendor-a/plc/CV-3"))
                .andExpect(content().json("{\"registers\":[0,101,1]}", JsonCompareMode.STRICT));
    }

    @Test
    void vendorA_unknownPlc_is404() throws Exception {
        mvc.perform(get("/sim/vendor-a/plc/SR-1")).andExpect(status().isNotFound());
        postJson("/sim/vendor-a/plc/CV-9/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":1}")
                .andExpect(status().isNotFound());
    }

    @Test
    void vendorA_startOp_restarts() throws Exception {
        sim.start("worker-in-zone");

        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":1}")
                .andExpect(status().isOk());

        assertThat(cv3().run()).isEqualTo(1);
    }

    @Test
    void vendorA_refusedDuringHold_is409() throws Exception {
        jam();

        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":1}")
                .andExpect(status().isConflict());

        assertThat(cv3().run()).isZero();
    }

    @Test
    void vendorA_otherOp_is400() throws Exception {
        sim.start("worker-in-zone");

        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\",\"op\":2}")
                .andExpect(status().isBadRequest());

        assertThat(cv3().run()).isZero();
    }

    @Test
    void vendorA_badBodies_are400_withoutEcho() throws Exception {
        postJson("/sim/vendor-a/plc/CV-3/command", "{\"op\":1}").andExpect(status().isBadRequest());
        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"" + uuid() + "\"}").andExpect(status().isBadRequest());
        postJson("/sim/vendor-a/plc/CV-3/command", "{\"cmdId\":\"secret-input\",\"op\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
        postJson("/sim/vendor-a/plc/CV-3/command", "not json secret-input")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
    }

    @Test
    void vendorB_readsSorter() throws Exception {
        mvc.perform(get("/sim/vendor-b/sorters/SR-1"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"opStatus":"ACTV",
                         "lanes":[
                          {"laneNo":1,"grp":"A","fill":30,"cap":40,"rate":10,"st":"OK"},
                          {"laneNo":2,"grp":"A","fill":30,"cap":40,"rate":10,"st":"OK"},
                          {"laneNo":3,"grp":"A","fill":30,"cap":40,"rate":20,"st":"OK"},
                          {"laneNo":4,"grp":"A","fill":30,"cap":40,"rate":20,"st":"OK"},
                          {"laneNo":5,"grp":"B","fill":30,"cap":40,"rate":10,"st":"OK"},
                          {"laneNo":6,"grp":"B","fill":30,"cap":40,"rate":10,"st":"OK"}],
                         "reroutes":[]}""", JsonCompareMode.STRICT));
    }

    @Test
    void vendorB_rerouteShowsUpAndClearsHold() throws Exception {
        jam();
        mvc.perform(get("/sim/vendor-b/sorters/SR-1"))
                .andExpect(jsonPath("$.opStatus").value("HOLD"))
                .andExpect(jsonPath("$.lanes[2].st").value("FULL"));

        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":4}")
                .andExpect(status().isOk());

        mvc.perform(get("/sim/vendor-b/sorters/SR-1"))
                .andExpect(jsonPath("$.opStatus").value("ACTV"))
                .andExpect(jsonPath("$.reroutes[0].src").value(3))
                .andExpect(jsonPath("$.reroutes[0].dst").value(4));
    }

    @Test
    void vendorB_refusedReroute_is409_unknownSorter_is404_badBody_is400() throws Exception {
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":3}")
                .andExpect(status().isConflict());
        mvc.perform(get("/sim/vendor-b/sorters/SR-2")).andExpect(status().isNotFound());
        postJson("/sim/vendor-b/sorters/SR-2/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":4}")
                .andExpect(status().isNotFound());
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"src\":3,\"dst\":4}").andExpect(status().isBadRequest());
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"dst\":4}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void vendorB_unknownLane_is404_chainedReroute_is409() throws Exception {
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":9}")
                .andExpect(status().isNotFound());
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":0,\"dst\":4}")
                .andExpect(status().isNotFound());

        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":3,\"dst\":4}")
                .andExpect(status().isOk());
        postJson("/sim/vendor-b/sorters/SR-1/reroutes", "{\"txn\":\"" + uuid() + "\",\"src\":4,\"dst\":5}")
                .andExpect(status().isConflict());
    }

    @Test
    void fleet_readsRobots_socAsFraction() throws Exception {
        sim.start("robot-battery");

        mvc.perform(get("/sim/fleet/robots"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"name":"AMR-01","mode":"AUTO","soc":0.82,"jobs":2,"maxJobs":4},
                         {"name":"AMR-02","mode":"CHARGING","soc":0.25,"jobs":2,"maxJobs":4},
                         {"name":"AMR-03","mode":"CHARGING","soc":0.25,"jobs":2,"maxJobs":4},
                         {"name":"AMR-04","mode":"AUTO","soc":0.9,"jobs":2,"maxJobs":4}]""", JsonCompareMode.STRICT));
    }

    @Test
    void fleet_reassign_movesJobs() throws Exception {
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-02\",\"to\":\"AMR-01\"}")
                .andExpect(status().isOk());

        assertThat(jobs("AMR-01")).isEqualTo(4);
        assertThat(jobs("AMR-02")).isZero();
    }

    @Test
    void fleet_unknown404_refused409_badBody400() throws Exception {
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-09\",\"to\":\"AMR-01\"}")
                .andExpect(status().isNotFound());
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-01\",\"to\":\"AMR-09\"}")
                .andExpect(status().isNotFound());
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"from\":\"AMR-01\",\"to\":\"AMR-01\"}")
                .andExpect(status().isConflict());
        postJson("/sim/fleet/reassign", "{\"from\":\"AMR-02\",\"to\":\"AMR-01\"}").andExpect(status().isBadRequest());
        postJson("/sim/fleet/reassign", "{\"cmdId\":\"" + uuid() + "\",\"to\":\"AMR-01\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void safety_readsZone_tsIsEpochMillisOfLastTick() throws Exception {
        Instant tick = START.plusSeconds(5);
        sim.tick(tick);
        sim.start("worker-in-zone");

        mvc.perform(get("/sim/safety/zones/Z1"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"estop\":0,\"presence\":1,\"ts\":" + tick.toEpochMilli() + "}", JsonCompareMode.STRICT));
    }

    @Test
    void safety_unknownZone_is404() throws Exception {
        mvc.perform(get("/sim/safety/zones/Z9")).andExpect(status().isNotFound());
    }

    @Test
    void safety_offline_is503() throws Exception {
        sim.start("safety-offline");

        mvc.perform(get("/sim/safety/zones/Z1")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void scenario_startRepeatConflictUnknown() throws Exception {
        mvc.perform(post("/sim/scenarios/chute-jam")).andExpect(status().isOk());
        mvc.perform(post("/sim/scenarios/chute-jam")).andExpect(status().isOk());
        mvc.perform(post("/sim/scenarios/robot-battery")).andExpect(status().isConflict());
        mvc.perform(post("/sim/scenarios/no-such")).andExpect(status().isNotFound());
    }

    @Test
    void reset_restoresTheLine() throws Exception {
        mvc.perform(post("/sim/scenarios/worker-in-zone")).andExpect(status().isOk());

        mvc.perform(post("/sim/reset")).andExpect(status().isOk());

        assertThat(cv3().run()).isEqualTo(1);
        mvc.perform(post("/sim/scenarios/robot-battery")).andExpect(status().isOk());
    }
}
