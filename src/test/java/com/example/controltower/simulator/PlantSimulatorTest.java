package com.example.controltower.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlantSimulatorTest {

    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");

    private final PlantSimulator sim = new PlantSimulator(42L, START);
    private Instant clock = START;
    private final List<SimEvent> seen = new ArrayList<>();

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            clock = clock.plus(Duration.ofSeconds(1));
            sim.tick(clock);
        }
        seen.addAll(sim.drainEvents());
    }

    private List<String> seenCodes() {
        seen.addAll(sim.drainEvents());
        return seen.stream().map(e -> e.equipmentId() + ":" + e.code()).toList();
    }

    private SimEvent lastEvent(String equipmentId, String code) {
        seen.addAll(sim.drainEvents());
        return seen.stream()
                .filter(e -> e.equipmentId().equals(equipmentId) && e.code().equals(code))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private SimSnapshot.Plc plc(String id) {
        return sim.snapshot().conveyors().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private SimSnapshot.Lane lane(int no) {
        return sim.snapshot().lanes().stream()
                .filter(l -> l.laneNo() == no)
                .findFirst()
                .orElseThrow();
    }

    private SimSnapshot.Robot robot(String name) {
        return sim.snapshot().robots().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private SimSnapshot.Safety safety(String zone) {
        return sim.snapshot().safety().stream()
                .filter(s -> s.zone().equals(zone))
                .findFirst()
                .orElseThrow();
    }

    private void jamUntilConveyorStops() {
        assertThat(sim.start("chute-jam")).isTrue();
        ticks(20);
        assertThat(plc("CV-3").run()).isZero();
    }

    private static UUID cmd() {
        return UUID.randomUUID();
    }

    @Test
    void initialState_matchesTheLine() {
        SimSnapshot s = sim.snapshot();

        assertThat(s.conveyors()).extracting(SimSnapshot.Plc::id).containsExactly("CV-1", "CV-2", "CV-3");
        assertThat(s.conveyors()).allSatisfy(c -> {
            assertThat(c.run()).isEqualTo(1);
            assertThat(c.alarm()).isZero();
            assertThat(c.zoneCode()).isEqualTo(1);
        });
        assertThat(s.opStatus()).isEqualTo("ACTV");
        assertThat(s.lanes()).extracting(SimSnapshot.Lane::laneNo).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(s.lanes()).extracting(SimSnapshot.Lane::grp).containsExactly("A", "A", "A", "A", "B", "B");
        assertThat(s.lanes()).extracting(SimSnapshot.Lane::rate).containsExactly(10, 10, 20, 20, 10, 10);
        assertThat(s.lanes()).allSatisfy(l -> {
            assertThat(l.cap()).isEqualTo(40);
            assertThat(l.fill()).isLessThan(100);
            assertThat(l.st()).isEqualTo("OK");
        });
        assertThat(s.reroutes()).isEmpty();
        assertThat(s.robots()).extracting(SimSnapshot.Robot::name).containsExactly("AMR-01", "AMR-02", "AMR-03", "AMR-04");
        assertThat(s.robots()).allSatisfy(r -> {
            assertThat(r.mode()).isEqualTo("AUTO");
            assertThat(r.soc()).isGreaterThanOrEqualTo(80);
            assertThat(r.jobs()).isEqualTo(2);
            assertThat(r.maxJobs()).isEqualTo(4);
        });
        assertThat(s.safety()).extracting(SimSnapshot.Safety::zone).containsExactly("Z1", "Z2", "Z3");
        assertThat(s.backlog()).isZero();
        assertThat(s.safetyOnline()).isTrue();
    }

    @Test
    void healthyLine_isSteadyAndSilent() {
        SimSnapshot before = sim.snapshot();

        ticks(30);

        assertThat(seen).isEmpty();
        assertThat(sim.snapshot().lanes()).isEqualTo(before.lanes());
    }

    @Test
    void sameSeedSameTimeline() {
        assertThat(timeline(7L)).isEqualTo(timeline(7L));
    }

    @Test
    void differentSeed_givesDifferentEventIds() {
        assertThat(timeline(7L).getFirst().eventId()).isNotEqualTo(timeline(8L).getFirst().eventId());
    }

    private static List<SimEvent> timeline(long seed) {
        PlantSimulator s = new PlantSimulator(seed, START);
        s.start("chute-jam");
        Instant t = START;
        for (int i = 0; i < 30; i++) {
            t = t.plusSeconds(1);
            s.tick(t);
        }
        return s.drainEvents();
    }

    @Test
    void chuteJam_cascade() {
        assertThat(sim.start("chute-jam")).isTrue();
        ticks(20);

        assertThat(seenCodes())
                .containsSubsequence("CH-03:CHUTE_FULL", "SR-1:LANE_BLOCKED", "CV-3:STOPPED");
        assertThat(lane(3).st()).isEqualTo("FULL");
        assertThat(lane(3).fill()).isEqualTo(100);
        assertThat(sim.snapshot().opStatus()).isEqualTo("HOLD");
        assertThat(lastEvent("SR-1", "LANE_BLOCKED").value()).isEqualTo(3);
        assertThat(plc("CV-3").run()).isZero();
        assertThat(plc("CV-3").alarm()).isEqualTo(101);

        int backlog = sim.snapshot().backlog();
        assertThat(backlog).isPositive();
        ticks(1);
        assertThat(sim.snapshot().backlog()).isGreaterThan(backlog);

        // 적체는 수치로만 번진다. 상류 설비가 멈추면 조치 한 번으로 끝나지 않는다.
        ticks(60);
        assertThat(sim.snapshot().backlog()).isGreaterThan(backlog);
        assertThat(plc("CV-2").run()).isEqualTo(1);
        assertThat(plc("CV-1").run()).isEqualTo(1);
        assertThat(seenCodes()).doesNotContain("CV-2:STOPPED", "CV-1:STOPPED");
    }

    @Test
    void eventTimes_strictlyIncrease_soRecoveryIsAfterTheFault() {
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);
        sim.restart(cmd(), "CV-3");
        sim.reset();

        List<Instant> times = new ArrayList<>(seen.stream().map(SimEvent::occurredAt).toList());
        times.addAll(sim.drainEvents().stream().map(SimEvent::occurredAt).toList());
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isAfter(times.get(i - 1));
        }
    }

    @Test
    void ratesAreReported_whileLineIsStopped() {
        jamUntilConveyorStops();

        assertThat(lane(3).rate()).isEqualTo(20);
        assertThat(lane(4).rate()).isEqualTo(20);
        assertThat(sim.snapshot().lanes().stream().mapToInt(SimSnapshot.Lane::rate).sum()).isEqualTo(80);
    }

    @Test
    void hold_refusesRestartOfEveryConveyor() {
        sim.start("chute-jam");
        ticks(2);
        assertThat(sim.snapshot().opStatus()).isEqualTo("HOLD");

        assertThat(sim.restart(cmd(), "CV-1")).isFalse();
        assertThat(sim.restart(cmd(), "CV-2")).isFalse();
        assertThat(sim.restart(cmd(), "CV-3")).isFalse();
    }

    @Test
    void restartIsRefused_whileHoldIsActive_andCanBeRetried() {
        jamUntilConveyorStops();
        UUID command = cmd();

        assertThat(sim.restart(command, "CV-3")).isFalse();
        assertThat(plc("CV-3").run()).isZero();
        assertThat(seenCodes()).doesNotContain("CV-3:RESTARTED");

        sim.divert(cmd(), 3, 4);
        assertThat(sim.restart(command, "CV-3")).isTrue();
        assertThat(plc("CV-3").run()).isEqualTo(1);
    }

    @Test
    void divertClearsBlock_conveyorStaysStopped() {
        jamUntilConveyorStops();

        assertThat(sim.divert(cmd(), 3, 4)).isTrue();

        assertThat(sim.snapshot().opStatus()).isEqualTo("ACTV");
        assertThat(sim.snapshot().reroutes()).containsExactly(new SimSnapshot.Reroute(3, 4));
        assertThat(lastEvent("SR-1", "LANE_CLEARED").value()).isEqualTo(3);
        ticks(3);
        assertThat(plc("CV-3").run()).isZero();
        assertThat(plc("CV-3").alarm()).isEqualTo(101);
    }

    @Test
    void divertBeforeConveyorStops_preventsTheStop() {
        sim.start("chute-jam");
        ticks(2);
        assertThat(seenCodes()).contains("SR-1:LANE_BLOCKED").doesNotContain("CV-3:STOPPED");

        sim.divert(cmd(), 3, 4);
        ticks(30);

        assertThat(plc("CV-3").run()).isEqualTo(1);
        assertThat(seenCodes()).doesNotContain("CV-3:STOPPED");
    }

    @Test
    void divertUpdatesRates_andAutoReleaseRestoresThem() {
        jamUntilConveyorStops();

        sim.divert(cmd(), 3, 4);

        assertThat(lane(3).rate()).isZero();
        assertThat(lane(4).rate()).isEqualTo(40);

        ticks(60);

        assertThat(sim.snapshot().reroutes()).isEmpty();
        assertThat(lane(3).rate()).isEqualTo(20);
        assertThat(lane(4).rate()).isEqualTo(20);
    }

    @Test
    void divertRejects_unknownLaneSameLaneAndChains() {
        assertThat(sim.divert(cmd(), 3, 3)).isFalse();
        assertThat(sim.divert(cmd(), 3, 9)).isFalse();
        assertThat(sim.divert(cmd(), 0, 4)).isFalse();

        assertThat(sim.divert(cmd(), 3, 4)).isTrue();
        assertThat(sim.divert(cmd(), 4, 5)).isFalse();
        assertThat(sim.divert(cmd(), 1, 3)).isFalse();
    }

    @Test
    void restartAfterDivert_backlogDecreases() {
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);
        int stopped = sim.snapshot().backlog();

        assertThat(sim.restart(cmd(), "CV-3")).isTrue();

        assertThat(plc("CV-3").run()).isEqualTo(1);
        assertThat(plc("CV-3").alarm()).isZero();
        assertThat(seenCodes()).contains("CV-3:RESTARTED");
        ticks(1);
        assertThat(sim.snapshot().backlog()).isLessThan(stopped);
        ticks(200);
        assertThat(sim.snapshot().backlog()).isZero();
        assertThat(sim.snapshot().conveyors()).allSatisfy(c -> {
            assertThat(c.run()).isEqualTo(1);
            assertThat(c.alarm()).isZero();
        });
        assertThat(seenCodes().stream().filter(c -> c.endsWith(":RESTARTED"))).containsExactly("CV-3:RESTARTED");
    }

    @Test
    void chuteDrained_divertAutoReleased_eventSent() {
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);

        ticks(1);
        assertThat(lane(3).fill()).isLessThan(100);
        assertThat(lane(3).st()).isEqualTo("OK");
        assertThat(seenCodes()).contains("CH-03:CHUTE_CLEARED").doesNotContain("SR-1:DIVERT_RELEASED");

        ticks(60);

        assertThat(lastEvent("SR-1", "DIVERT_RELEASED").value()).isEqualTo(3);
        assertThat(sim.snapshot().reroutes()).isEmpty();
        // 재가동 전에도 우회 해제는 멈춘 설비를 움직이지 않는다.
        assertThat(plc("CV-3").run()).isZero();
    }

    @Test
    void autoRelease_doesNotJamTheLaneAgain() {
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);
        ticks(60);
        seen.clear();

        ticks(30);

        assertThat(seenCodes()).doesNotContain("CH-03:CHUTE_FULL", "SR-1:LANE_BLOCKED");
    }

    @Test
    void duplicateCommandId_appliedOnce() {
        sim.start("robot-battery");
        ticks(1);
        UUID command = cmd();

        assertThat(sim.reassign(command, "AMR-02", "AMR-01")).isTrue();
        assertThat(sim.reassign(command, "AMR-02", "AMR-01")).isTrue();

        assertThat(robot("AMR-01").jobs()).isEqualTo(4);
        assertThat(robot("AMR-02").jobs()).isZero();

        jamUntilRestartable();
        UUID restart = cmd();
        assertThat(sim.restart(restart, "CV-3")).isTrue();
        assertThat(sim.restart(restart, "CV-3")).isTrue();
        assertThat(seenCodes().stream().filter("CV-3:RESTARTED"::equals)).hasSize(1);
    }

    private void jamUntilRestartable() {
        sim.reset();
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);
    }

    @Test
    void reassignRejects_unknownSameFullAndEmpty() {
        assertThat(sim.reassign(cmd(), "AMR-01", "AMR-01")).isFalse();
        assertThat(sim.reassign(cmd(), "AMR-09", "AMR-01")).isFalse();
        assertThat(sim.reassign(cmd(), "AMR-02", "AMR-01")).isTrue();
        assertThat(sim.reassign(cmd(), "AMR-02", "AMR-03")).isFalse();
        assertThat(sim.reassign(cmd(), "AMR-03", "AMR-01")).isFalse();
    }

    @Test
    void reassignRejects_unknownTarget() {
        assertThat(sim.reassign(cmd(), "AMR-01", "AMR-09")).isFalse();
        assertThat(robot("AMR-01").jobs()).isEqualTo(2);
    }

    @Test
    void reassign_movesOnlyWhatTheTargetHasRoomFor() {
        sim.reassign(cmd(), "AMR-01", "AMR-02");
        assertThat(robot("AMR-02").jobs()).isEqualTo(4);

        assertThat(sim.reassign(cmd(), "AMR-02", "AMR-03")).isTrue();

        assertThat(robot("AMR-02").jobs()).isEqualTo(2);
        assertThat(robot("AMR-03").jobs()).isEqualTo(4);
    }

    @Test
    void replayedRestart_afterReset_doesNotMoveAStoppedConveyor() {
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);
        UUID restart = cmd();
        assertThat(sim.restart(restart, "CV-3")).isTrue();
        sim.reset();
        sim.start("worker-in-zone");
        sim.drainEvents();

        assertThat(sim.restart(restart, "CV-3")).isTrue();

        assertThat(plc("CV-3").run()).isZero();
        assertThat(sim.drainEvents()).isEmpty();
    }

    @Test
    void replayedDivert_afterAutoRelease_doesNotDivertAgain() {
        jamUntilConveyorStops();
        UUID divert = cmd();
        sim.divert(divert, 3, 4);
        ticks(60);
        assertThat(sim.snapshot().reroutes()).isEmpty();

        assertThat(sim.divert(divert, 3, 4)).isTrue();

        assertThat(sim.snapshot().reroutes()).isEmpty();
    }

    @Test
    void restartRejects_unknownConveyor() {
        assertThat(sim.restart(cmd(), "SR-1")).isFalse();
    }

    @Test
    void workerInZone_setsPresence() {
        assertThat(sim.start("worker-in-zone")).isTrue();
        ticks(1);

        assertThat(plc("CV-3").run()).isZero();
        assertThat(plc("CV-3").alarm()).isZero();
        assertThat(seenCodes()).contains("CV-3:STOPPED");
        assertThat(safety("Z1").presence()).isEqualTo(1);
        assertThat(safety("Z2").presence()).isZero();
        assertThat(sim.snapshot().backlog()).isZero();
    }

    @Test
    void safetyTimestamp_followsLastTick() {
        ticks(3);

        assertThat(safety("Z1").ts()).isEqualTo(clock);
    }

    @Test
    void safetyOffline_flag() {
        assertThat(sim.start("safety-offline")).isTrue();
        assertThat(sim.snapshot().safetyOnline()).isFalse();

        sim.setSafetyOnline(true);
        assertThat(sim.snapshot().safetyOnline()).isTrue();
    }

    @Test
    void robotBattery_lowersTwoRobots() {
        assertThat(sim.start("robot-battery")).isTrue();
        ticks(1);

        for (String id : List.of("AMR-02", "AMR-03")) {
            assertThat(robot(id).soc()).isEqualTo(25);
            assertThat(robot(id).mode()).isEqualTo("CHARGING");
            assertThat(robot(id).jobs()).isEqualTo(2);
            assertThat(lastEvent(id, "BATTERY_LOW").value()).isEqualTo(25);
        }
        assertThat(robot("AMR-01").mode()).isEqualTo("AUTO");
        assertThat(robot("AMR-04").mode()).isEqualTo("AUTO");
    }

    @Test
    void scannerDegraded_emitsEventWithRate() {
        assertThat(sim.start("scanner-degraded")).isTrue();
        ticks(1);

        assertThat(lastEvent("SC-1", "READ_DEGRADED").value()).isEqualTo(18);
    }

    @Test
    void reset_restoresInitial() {
        SimSnapshot initial = sim.snapshot();
        sim.start("chute-jam");
        sim.setSafetyOnline(false);
        ticks(30);
        sim.divert(cmd(), 3, 4);

        sim.reset();

        assertThat(sim.snapshot().safety()).allSatisfy(z -> assertThat(z.ts()).isEqualTo(clock));
        SimSnapshot after = sim.snapshot();
        assertThat(after.conveyors()).isEqualTo(initial.conveyors());
        assertThat(after.opStatus()).isEqualTo(initial.opStatus());
        assertThat(after.lanes()).isEqualTo(initial.lanes());
        assertThat(after.reroutes()).isEqualTo(initial.reroutes());
        assertThat(after.robots()).isEqualTo(initial.robots());
        assertThat(after.backlog()).isZero();
        assertThat(after.safetyOnline()).isTrue();
    }

    @Test
    void reset_clearsTheRunningScenario() {
        sim.start("chute-jam");

        sim.reset();

        assertThat(sim.start("robot-battery")).isTrue();
    }

    @Test
    void reset_emitsRecoveryForEachAbnormalState() {
        jamUntilConveyorStops();
        sim.drainEvents();
        sim.reset();
        assertThat(sim.drainEvents()).extracting(e -> e.equipmentId() + ":" + e.code())
                .contains("CV-3:RESTARTED", "SR-1:LANE_CLEARED", "CH-03:CHUTE_CLEARED");

        sim.start("scanner-degraded");
        ticks(1);
        sim.reset();
        SimEvent read = sim.drainEvents().getFirst();
        assertThat(read.code()).isEqualTo("READ_NORMAL");
        assertThat(read.equipmentId()).isEqualTo("SC-1");
        assertThat(read.value()).isLessThan(18);

        sim.start("robot-battery");
        ticks(1);
        sim.reset();
        assertThat(sim.drainEvents()).extracting(e -> e.equipmentId() + ":" + e.code())
                .containsExactly("AMR-02:BATTERY_OK", "AMR-03:BATTERY_OK");
    }

    @Test
    void reset_chuteClearedCarriesTheNewFill() {
        jamUntilConveyorStops();

        sim.reset();

        assertThat(lastEvent("CH-03", "CHUTE_CLEARED").value()).isEqualTo(lane(3).fill());
    }

    @Test
    void reset_releasesActiveDiversion() {
        jamUntilConveyorStops();
        sim.divert(cmd(), 3, 4);
        sim.drainEvents();

        sim.reset();

        assertThat(sim.drainEvents()).extracting(e -> e.equipmentId() + ":" + e.code())
                .contains("SR-1:DIVERT_RELEASED");
    }

    @Test
    void reset_keepsUndrainedEvents_andNeverReusesEventIds() {
        sim.start("scanner-degraded");
        sim.tick(clock = clock.plusSeconds(1));

        sim.reset();

        List<SimEvent> events = new ArrayList<>(sim.drainEvents());
        assertThat(events).extracting(SimEvent::code).containsExactly("READ_DEGRADED", "READ_NORMAL");

        sim.start("scanner-degraded");
        sim.tick(clock = clock.plusSeconds(1));
        events.addAll(sim.drainEvents());
        assertThat(new HashSet<>(events.stream().map(SimEvent::eventId).toList())).hasSize(3);
    }

    @Test
    void reset_whenHealthy_emitsNothing() {
        sim.reset();

        assertThat(sim.drainEvents()).isEmpty();
    }

    @Test
    void scenarioConflict() {
        assertThat(sim.start("chute-jam")).isTrue();
        assertThat(sim.start("chute-jam")).isTrue();
        assertThat(sim.start("robot-battery")).isFalse();
        assertThat(sim.start("no-such-scenario")).isFalse();
    }

    @Test
    void repeatingTheRunningScenario_doesNotReapplyIt() {
        sim.start("chute-jam");
        ticks(1);
        sim.start("chute-jam");
        ticks(1);

        assertThat(seenCodes().stream().filter("CH-03:CHUTE_FULL"::equals)).hasSize(1);
    }

    @Test
    void scenarioNames_canBeLookedUp() {
        assertThat(Scenario.byName("chute-jam")).contains(Scenario.CHUTE_JAM);
        assertThat(Scenario.byName("no-such-scenario")).isEmpty();
    }

    @Test
    void drainEvents_emptiesTheQueue() {
        sim.start("scanner-degraded");
        ticks(1);

        assertThat(sim.drainEvents()).isEmpty();
    }
}
