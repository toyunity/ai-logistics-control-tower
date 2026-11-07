package com.example.controltower.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 현장 설비를 대신하는 틱 기반 상태 기계. 시드와 입력 시각에만 의존한다.
 * 시나리오는 start에서 바로 시작되고 이후 전개는 틱이 맡는다. 명령은 호출 안에서 적용되어 바로 읽어 확인할 수 있다.
 * HTTP 스레드와 틱 스레드가 공유하므로 공개 메서드를 모두 동기화한다.
 */
public final class PlantSimulator {

    private static final int JAM_LANE = 3;
    private static final int ALARM_DOWNSTREAM_BLOCKED = 101;
    private static final int LOW_SOC = 25;
    private static final int DEGRADED_READ_FAIL_PERCENT = 18;

    // CV-3가 시나리오 시작 후 20틱 안에 멈추도록 맞춘 값이다.
    private static final int BLOCK_AFTER_TICKS = 2;
    private static final int STOP_AFTER_HOLD_TICKS = 5;
    private static final int BACKLOG_GROWTH = 8;
    private static final int BACKLOG_DRAIN = 10;

    // 재가동 확정까지 시간을 남기려고 천천히 비운다.
    private static final int CHUTE_DRAIN = 2;
    private static final int RELEASE_FILL = 20;

    private final long seed;
    private final List<SimEvent> pending = new ArrayList<>();
    // 리셋해도 지우지 않는다. 리셋 전 명령의 재전송이 초기 상태에 적용되면 안 된다.
    private final Set<UUID> applied = new HashSet<>();

    private SimState state = new SimState();
    private Scenario running;
    private Instant now;
    private Instant lastEventAt;
    // 리셋해도 되돌리지 않는다. 수신 측이 ID로 중복을 거르므로 재사용하면 새 이벤트가 버려진다.
    private long sequence;

    public PlantSimulator(long seed, Instant start) {
        this.seed = seed;
        this.now = start;
    }

    public synchronized void tick(Instant now) {
        this.now = now;
        blockFullLane();
        stopOnHold();
        growOrShrinkBacklog();
        drainDivertedLanes();
    }

    public synchronized List<SimEvent> drainEvents() {
        List<SimEvent> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    /** 다른 시나리오가 진행 중이거나 이름을 모르면 false. 구분이 필요하면 {@link Scenario#byName}을 쓴다. */
    public synchronized boolean start(String name) {
        Optional<Scenario> scenario = Scenario.byName(name);
        if (scenario.isEmpty() || (running != null && running != scenario.get())) {
            return false;
        }
        if (running == null) {
            running = scenario.get();
            apply(running);
        }
        return true;
    }

    private void apply(Scenario scenario) {
        switch (scenario) {
            case CHUTE_JAM -> {
                SimState.Lane lane = state.lane(JAM_LANE);
                lane.fill = SimState.FULL;
                emit(lane.id(), "CHUTE_FULL", lane.fill);
            }
            case ROBOT_BATTERY -> {
                lowerBattery("AMR-02");
                lowerBattery("AMR-03");
            }
            case SCANNER_DEGRADED -> {
                state.readFailPercent = DEGRADED_READ_FAIL_PERCENT;
                emit("SC-1", "READ_DEGRADED", DEGRADED_READ_FAIL_PERCENT);
            }
            case WORKER_IN_ZONE -> {
                stop("CV-3", 0);
                state.z1Presence = 1;
            }
            case SAFETY_OFFLINE -> state.safetyOnline = false;
        }
    }

    private void lowerBattery(String id) {
        SimState.Robot robot = state.robots.get(id);
        robot.soc = LOW_SOC;
        robot.mode = "CHARGING";
        emit(id, "BATTERY_LOW", LOW_SOC);
    }

    /** 초기 상태로 돌리고 비정상이던 항목마다 복구 이벤트를 낸다. 스캐너 상태는 이벤트로만 전달되어 이것 없이는 장애 건이 닫히지 않는다. */
    public synchronized void reset() {
        SimState old = state;
        state = new SimState();
        running = null;
        old.conveyors.forEach((id, c) -> {
            if (c.run == 0) {
                emit(id, "RESTARTED", null);
            }
        });
        if (old.holdLane != 0) {
            emit("SR-1", "LANE_CLEARED", old.holdLane);
        }
        old.reroutes.forEach(r -> emit("SR-1", "DIVERT_RELEASED", r.src()));
        old.lanes.stream().filter(SimState.Lane::full).forEach(l -> emit(l.id(), "CHUTE_CLEARED", null));
        old.robots.values().stream()
                .filter(r -> !r.mode.equals("AUTO"))
                .forEach(r -> emit(r.name, "BATTERY_OK", state.robots.get(r.name).soc));
        if (old.readFailPercent != SimState.NORMAL_READ_FAIL_PERCENT) {
            emit("SC-1", "READ_NORMAL", SimState.NORMAL_READ_FAIL_PERCENT);
        }
    }

    /** 거절한 명령은 기록하지 않는다. 원인이 풀린 뒤 같은 명령 ID로 다시 보낼 수 있어야 한다. */
    public synchronized boolean restart(UUID cmd, String id) {
        if (applied.contains(cmd)) {
            return true;
        }
        SimState.Conveyor conveyor = state.conveyors.get(id);
        // 분류기 차단 중에는 설비 자체의 인터록이 재가동을 막는다.
        if (conveyor == null || state.holdLane != 0) {
            return false;
        }
        if (conveyor.run == 0) {
            conveyor.run = 1;
            conveyor.alarm = 0;
            emit(id, "RESTARTED", null);
        }
        applied.add(cmd);
        return true;
    }

    public synchronized boolean divert(UUID cmd, int fromLane, int toLane) {
        if (applied.contains(cmd)) {
            return true;
        }
        boolean invalid = fromLane == toLane || state.lane(fromLane) == null || state.lane(toLane) == null;
        // 연쇄 우회는 물량 합계가 맞지 않으므로 받지 않는다.
        boolean chained = state.reroutes.stream()
                .anyMatch(r -> r.src() == fromLane || r.src() == toLane || r.dst() == fromLane);
        if (invalid || chained) {
            return false;
        }
        state.reroutes.add(new SimSnapshot.Reroute(fromLane, toLane));
        if (state.holdLane == fromLane) {
            state.holdLane = 0;
            emit("SR-1", "LANE_CLEARED", fromLane);
        }
        applied.add(cmd);
        return true;
    }

    public synchronized boolean reassign(UUID cmd, String from, String to) {
        if (applied.contains(cmd)) {
            return true;
        }
        SimState.Robot source = state.robots.get(from);
        SimState.Robot target = state.robots.get(to);
        if (source == null || target == null || source == target) {
            return false;
        }
        int moved = Math.min(source.jobs, SimState.MAX_JOBS - target.jobs);
        if (moved <= 0) {
            return false;
        }
        source.jobs -= moved;
        target.jobs += moved;
        applied.add(cmd);
        return true;
    }

    public synchronized void setSafetyOnline(boolean online) {
        state.safetyOnline = online;
    }

    public synchronized SimSnapshot snapshot() {
        return state.snapshot(now);
    }

    private void blockFullLane() {
        if (state.holdLane != 0) {
            return;
        }
        for (SimState.Lane lane : state.lanes) {
            boolean diverted = state.reroutes.stream().anyMatch(r -> r.src() == lane.no);
            if (lane.full() && !diverted && ++state.fullTicks >= BLOCK_AFTER_TICKS) {
                state.holdLane = lane.no;
                state.holdTicks = 0;
                emit("SR-1", "LANE_BLOCKED", lane.no);
                return;
            }
        }
    }

    private void stopOnHold() {
        if (state.holdLane != 0 && ++state.holdTicks >= STOP_AFTER_HOLD_TICKS) {
            stop("CV-3", ALARM_DOWNSTREAM_BLOCKED);
        }
    }

    // 상류로의 전파는 적체 수치로만 나타낸다. 설비까지 세우면 조치 순서가 재가동 한 번으로 끝나지 않는다.
    private void growOrShrinkBacklog() {
        SimState.Conveyor cv3 = state.conveyors.get("CV-3");
        if (cv3.run == 1) {
            state.backlog = Math.max(0, state.backlog - BACKLOG_DRAIN);
        } else if (cv3.alarm == ALARM_DOWNSTREAM_BLOCKED) {
            state.backlog += BACKLOG_GROWTH;
        }
    }

    private void drainDivertedLanes() {
        for (SimSnapshot.Reroute reroute : List.copyOf(state.reroutes)) {
            SimState.Lane lane = state.lane(reroute.src());
            boolean wasFull = lane.full();
            lane.fill = Math.max(0, lane.fill - CHUTE_DRAIN);
            if (wasFull) {
                emit(lane.id(), "CHUTE_CLEARED", lane.fill);
            }
            if (lane.fill <= RELEASE_FILL) {
                state.reroutes.remove(reroute);
                emit("SR-1", "DIVERT_RELEASED", lane.no);
            }
        }
    }

    private void stop(String id, int alarm) {
        SimState.Conveyor conveyor = state.conveyors.get(id);
        if (conveyor.run == 1) {
            conveyor.run = 0;
            conveyor.alarm = alarm;
            emit(id, "STOPPED", null);
        }
    }

    // 원인 분석은 복구가 고장보다 엄밀히 뒤일 때만 복구로 본다. 명령과 리셋에는 시각 입력이 없어 같은 틱 안에서 시각이 겹치므로 밀어낸다.
    private void emit(String id, String code, Integer value) {
        Instant at = lastEventAt != null && !now.isAfter(lastEventAt) ? lastEventAt.plusMillis(1) : now;
        lastEventAt = at;
        pending.add(new SimEvent(new UUID(seed, ++sequence).toString(), id, code, value, at));
    }
}
