package com.example.controltower.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SafetyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final Decision ALLOWED = new Decision.Allowed();

    private final SafetyPolicy policy = new SafetyPolicy(new SafetyLimits(Duration.ofSeconds(3), 30));

    private static Decision denied(DenialCode code) {
        return new Decision.Denied(code);
    }

    // restart

    private static final ConveyorStatus STOPPED_CONVEYOR = conveyor(RunState.STOPPED, FaultClass.NONE);
    private static final SorterStatus CLEAR_SORTER = sorter(RunState.RUNNING, false);

    private static ConveyorStatus conveyor(RunState state, FaultClass fault) {
        return new ConveyorStatus("CV-3", "Z1", state, fault);
    }

    private static Optional<SafetyStatus> signal(boolean emergencyStop, boolean workerPresent, Instant measuredAt) {
        return Optional.of(new SafetyStatus("Z1", emergencyStop, workerPresent, measuredAt));
    }

    private static Optional<SafetyStatus> clearSignalAt(Instant measuredAt) {
        return signal(false, false, measuredAt);
    }

    private Decision restart(ConveyorStatus conveyor) {
        return policy.restart(conveyor, CLEAR_SORTER, clearSignalAt(NOW), NOW);
    }

    @Test
    void restart_allNormal_allowed() {
        assertThat(restart(STOPPED_CONVEYOR)).isEqualTo(ALLOWED);
    }

    @Test
    void restart_noSignal_denied() {
        assertThat(policy.restart(STOPPED_CONVEYOR, CLEAR_SORTER, Optional.empty(), NOW))
                .isEqualTo(denied(DenialCode.SAFETY_SIGNAL_UNKNOWN));
    }

    @Test
    void restart_signalJustOverMaxAge_denied() {
        Optional<SafetyStatus> stale = clearSignalAt(NOW.minusMillis(3001));
        assertThat(policy.restart(STOPPED_CONVEYOR, CLEAR_SORTER, stale, NOW))
                .isEqualTo(denied(DenialCode.SAFETY_SIGNAL_STALE));
    }

    @Test
    void restart_signalExactlyMaxAge_allowed() {
        Optional<SafetyStatus> edge = clearSignalAt(NOW.minusSeconds(3));
        assertThat(policy.restart(STOPPED_CONVEYOR, CLEAR_SORTER, edge, NOW)).isEqualTo(ALLOWED);
    }

    @Test
    void restart_emergencyStop_denied() {
        assertThat(policy.restart(STOPPED_CONVEYOR, CLEAR_SORTER, signal(true, false, NOW), NOW))
                .isEqualTo(denied(DenialCode.EMERGENCY_STOP_ACTIVE));
    }

    @Test
    void restart_workerInZone_denied() {
        assertThat(policy.restart(STOPPED_CONVEYOR, CLEAR_SORTER, signal(false, true, NOW), NOW))
                .isEqualTo(denied(DenialCode.WORKER_IN_ZONE));
    }

    @Test
    void restart_conveyorUnknown_denied() {
        assertThat(restart(conveyor(RunState.UNKNOWN, FaultClass.NONE))).isEqualTo(denied(DenialCode.STATE_UNKNOWN));
    }

    @Test
    void restart_faultUnknown_denied() {
        assertThat(restart(conveyor(RunState.STOPPED, FaultClass.UNKNOWN))).isEqualTo(denied(DenialCode.STATE_UNKNOWN));
    }

    @Test
    void restart_sorterUnknown_denied() {
        assertThat(policy.restart(STOPPED_CONVEYOR, sorter(RunState.UNKNOWN, false), clearSignalAt(NOW), NOW))
                .isEqualTo(denied(DenialCode.STATE_UNKNOWN));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"RUNNING", "FAULT", "DEGRADED", "OFFLINE"})
    void restart_notStopped_denied(RunState state) {
        assertThat(restart(conveyor(state, FaultClass.NONE))).isEqualTo(denied(DenialCode.NOT_STOPPED));
    }

    @Test
    void restart_sorterBlocked_denied() {
        assertThat(policy.restart(STOPPED_CONVEYOR, sorter(RunState.RUNNING, true), clearSignalAt(NOW), NOW))
                .isEqualTo(denied(DenialCode.SORTER_BLOCKED));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"STOPPED", "FAULT"})
    void restart_mechanicalFault_denied(RunState state) {
        assertThat(restart(conveyor(state, FaultClass.MECHANICAL))).isEqualTo(denied(DenialCode.MECHANICAL_FAULT));
    }

    @Test
    void restart_downstreamBlockedButStopped_allowed() {
        assertThat(restart(conveyor(RunState.STOPPED, FaultClass.DOWNSTREAM_BLOCKED))).isEqualTo(ALLOWED);
    }

    @Test
    void restart_safetyReasonComesBeforeEquipmentReason() {
        ConveyorStatus broken = conveyor(RunState.UNKNOWN, FaultClass.MECHANICAL);
        assertThat(policy.restart(broken, sorter(RunState.RUNNING, true), signal(false, true, NOW), NOW))
                .isEqualTo(denied(DenialCode.WORKER_IN_ZONE));
    }

    // divert

    private static ChuteStatus chute(String id, String group, RunState state, int rate, int capacity, boolean full) {
        return new ChuteStatus(id, group, state, 50, rate, capacity, full);
    }

    private static ChuteStatus source() {
        return chute("CH-03", "A", RunState.RUNNING, 20, 40, false);
    }

    private static ChuteStatus target() {
        return chute("CH-04", "A", RunState.RUNNING, 20, 40, false);
    }

    private static SorterStatus sorter(RunState state, boolean blocked, ChuteStatus... chutes) {
        return new SorterStatus("SR-1", state, blocked, List.of(chutes), Map.of());
    }

    private Decision divert(ChuteStatus from, ChuteStatus to) {
        return policy.divert(sorter(RunState.RUNNING, false, from, to), "CH-03", "CH-04");
    }

    @Test
    void divert_loadEqualsCapacity_allowed() {
        assertThat(divert(source(), target())).isEqualTo(ALLOWED);
    }

    @Test
    void divert_loadOverCapacity_denied() {
        ChuteStatus busy = chute("CH-04", "A", RunState.RUNNING, 21, 40, false);
        assertThat(divert(source(), busy)).isEqualTo(denied(DenialCode.OVER_CAPACITY));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void divert_sorterNotRunning_denied(RunState state) {
        assertThat(policy.divert(sorter(state, false, source(), target()), "CH-03", "CH-04"))
                .isEqualTo(denied(DenialCode.SORTER_NOT_RUNNING));
    }

    @Test
    void divert_unknownChute_denied() {
        SorterStatus sorter = sorter(RunState.RUNNING, false, source(), target());
        assertThat(policy.divert(sorter, "CH-99", "CH-04")).isEqualTo(denied(DenialCode.NOT_FOUND));
        assertThat(policy.divert(sorter, "CH-03", "CH-99")).isEqualTo(denied(DenialCode.NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void divert_targetNotNormal_denied(RunState state) {
        ChuteStatus broken = chute("CH-04", "A", state, 20, 40, false);
        assertThat(divert(source(), broken)).isEqualTo(denied(DenialCode.CHUTE_UNAVAILABLE));
    }

    @Test
    void divert_targetFull_denied() {
        ChuteStatus full = chute("CH-04", "A", RunState.RUNNING, 20, 40, true);
        assertThat(divert(source(), full)).isEqualTo(denied(DenialCode.CHUTE_FULL));
    }

    @Test
    void divert_differentGroup_denied() {
        ChuteStatus otherGroup = chute("CH-04", "B", RunState.RUNNING, 20, 40, false);
        assertThat(divert(source(), otherGroup)).isEqualTo(denied(DenialCode.GROUP_MISMATCH));
    }

    @Test
    void divert_sourceStateUnknown_denied() {
        ChuteStatus unknown = chute("CH-03", "A", RunState.UNKNOWN, 20, 40, false);
        assertThat(divert(unknown, target())).isEqualTo(denied(DenialCode.STATE_UNKNOWN));
    }

    // chute-jam 시나리오의 우회 단계: 차단된 분류기와 만재 원본이 우회의 이유이므로 막으면 안 된다.
    @Test
    void divert_blockedSorterAndFullSource_allowed() {
        ChuteStatus jammed = chute("CH-03", "A", RunState.RUNNING, 20, 40, true);
        assertThat(policy.divert(sorter(RunState.RUNNING, true, jammed, target()), "CH-03", "CH-04"))
                .isEqualTo(ALLOWED);
    }

    // reassign

    private static RobotStatus robot(RunState state, int battery, int queued, int capacity) {
        return new RobotStatus("AMR-01", state, battery, queued, capacity);
    }

    private static final RobotStatus BUSY_ROBOT = robot(RunState.RUNNING, 80, 2, 4);

    @Test
    void reassign_batteryAtMinimum_allowed() {
        assertThat(policy.reassign(BUSY_ROBOT, robot(RunState.RUNNING, 30, 0, 4))).isEqualTo(ALLOWED);
    }

    @Test
    void reassign_sourceOfflineWithTasks_allowed() {
        assertThat(policy.reassign(robot(RunState.OFFLINE, 10, 2, 4), robot(RunState.RUNNING, 80, 0, 4)))
                .isEqualTo(ALLOWED);
    }

    @Test
    void reassign_sourceHasNoTasks_denied() {
        assertThat(policy.reassign(robot(RunState.RUNNING, 80, 0, 4), robot(RunState.RUNNING, 80, 0, 4)))
                .isEqualTo(denied(DenialCode.NO_TASKS));
    }

    @Test
    void reassign_sourceStateUnknown_denied() {
        assertThat(policy.reassign(robot(RunState.UNKNOWN, 80, 2, 4), robot(RunState.RUNNING, 80, 0, 4)))
                .isEqualTo(denied(DenialCode.STATE_UNKNOWN));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"OFFLINE", "UNKNOWN", "FAULT"})
    void reassign_targetUnavailable_denied(RunState state) {
        assertThat(policy.reassign(BUSY_ROBOT, robot(state, 80, 0, 4))).isEqualTo(denied(DenialCode.ROBOT_OFFLINE));
    }

    @Test
    void reassign_batteryBelowMinimum_denied() {
        assertThat(policy.reassign(BUSY_ROBOT, robot(RunState.RUNNING, 29, 0, 4)))
                .isEqualTo(denied(DenialCode.LOW_BATTERY));
    }

    @Test
    void reassign_queueFull_denied() {
        assertThat(policy.reassign(BUSY_ROBOT, robot(RunState.RUNNING, 80, 4, 4)))
                .isEqualTo(denied(DenialCode.QUEUE_FULL));
    }

    @ParameterizedTest
    @EnumSource(DenialCode.class)
    void everyDenialCode_hasFixedMessage(DenialCode code) {
        assertThat(code.message()).isNotBlank();
    }
}
