package com.example.controltower.application.service;

import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.Severity;
import com.example.controltower.domain.SeverityPolicy;
import com.example.controltower.domain.SorterStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 읽기에 실패한 설비군의 장애 건은 열지도 닫지도 않고, 상태를 모르는 설비의 건은 닫지 않는다.
 * 모르는 것을 정상으로 치면 진행 중인 장애가 화면에서 사라진다.
 * 다만 슈트가 만재를 직접 보고하면 상태가 UNKNOWN이나 OFFLINE이어도 연다.
 * 열린 건의 등급은 내려가지 않는다. 늦은 이벤트나 일시적 조회 실패가 진행 중인 장애를 가볍게 보이게 해서는 안 된다.
 */
public final class DetectAnomaliesService implements DetectAnomalies {

    private static final Logger log = System.getLogger(DetectAnomaliesService.class.getName());

    private static final String CONVEYOR_STOPPED = "CONVEYOR_STOPPED";
    private static final String CHUTE_FULL = "CHUTE_FULL";
    private static final String SCANNER_DEGRADED = "SCANNER_DEGRADED";
    private static final String ROBOT_BATTERY = "ROBOT_BATTERY";
    private static final String SCANNER = "SC-1";
    private static final String FLEET = "AMR-FLEET";
    private static final int LOW_BATTERY_PERCENT = 30;
    private static final int LOW_BATTERY_ROBOTS = 2;
    private static final Set<String> STOP_CODES = Set.of("STOPPED", "FAULT");

    private final Conveyors conveyors;
    private final Sorters sorters;
    private final RobotFleet fleet;
    private final EventLog events;
    private final Incidents incidents;
    private final LineTopology topology;
    private final Clock clock;
    private final LocalTime cutoff;
    private final ReentrantLock cycleLock = new ReentrantLock();

    public DetectAnomaliesService(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            EventLog events,
            Incidents incidents,
            LineTopology topology,
            Clock clock,
            LocalTime cutoff) {
        this.conveyors = conveyors;
        this.sorters = sorters;
        this.fleet = fleet;
        this.events = events;
        this.incidents = incidents;
        this.topology = topology;
        this.clock = clock;
        this.cutoff = cutoff;
    }

    /** @param active 조건에 해당하는 설비와 등급 @param cleared 정상임을 확인한 설비. 어느 쪽에도 없으면 모르는 설비다. */
    private record Reading(Map<String, Severity> active, Set<String> cleared) {}

    private record Cycle(Instant now, Duration untilCutoff, long ratePerMin, List<Incident> open) {}

    /**
     * 스케줄러와 이벤트 수신이 동시에 부른다. 먼저 읽은 낡은 주기가 다른 주기가 막 닫은 건을 다시 열지 않도록 겹치지 않게 하고,
     * 수신 스레드가 게이트웨이 I/O 뒤에 줄 서지 않도록 기다리지 않고 돌아간다. 놓친 이벤트는 5초 주기가 반영한다.
     * 중복 열기는 저장소의 부분 유일 인덱스가 막는다.
     */
    @Override
    public void detect() {
        if (!cycleLock.tryLock()) {
            return;
        }
        try {
            runCycle();
        } finally {
            cycleLock.unlock();
        }
    }

    private void runCycle() {
        Instant now = clock.instant();
        List<Incident> open = incidents.open();
        Optional<SorterStatus> sorter = readSorter();
        Cycle cycle = new Cycle(
                now,
                Duration.between(now, LocalDate.now(clock).atTime(cutoff).atZone(clock.getZone()).toInstant()),
                sorter.stream().flatMap(s -> s.chutes().stream()).mapToLong(ChuteStatus::ratePerMin).sum(),
                open);

        reconcile(CONVEYOR_STOPPED, () -> conveyorReading(cycle), open, now);
        sorter.ifPresent(s -> reconcile(CHUTE_FULL, () -> chuteReading(s, cycle), open, now));
        reconcile(SCANNER_DEGRADED, () -> scannerReading(cycle), open, now);
        reconcile(ROBOT_BATTERY, () -> robotReading(cycle), open, now);
    }

    private Optional<SorterStatus> readSorter() {
        try {
            return Optional.of(sorters.get());
        } catch (RuntimeException e) {
            logSkipped(CHUTE_FULL, e);
            return Optional.empty();
        }
    }

    private Reading conveyorReading(Cycle cycle) {
        Reading reading = new Reading(new HashMap<>(), new HashSet<>());
        for (ConveyorStatus c : conveyors.all()) {
            if (!topology.isConveyor(c.id())) {
                continue;
            }
            if (isStop(c.state())) {
                reading.active().put(c.id(), grade(conveyorStopped(c.id(), cycle), cycle));
            } else if (isKnown(c.state())) {
                reading.cleared().add(c.id());
            }
        }
        return reading;
    }

    private Reading chuteReading(SorterStatus sorter, Cycle cycle) {
        Reading reading = new Reading(new HashMap<>(), new HashSet<>());
        for (ChuteStatus c : sorter.chutes()) {
            if (!topology.isChute(c.id())) {
                continue;
            }
            if (c.full()) {
                // 슈트가 멈춰 있으면 그 정지 시간으로 등급을 매긴다. 열린 시각은 쓰지 않는다. 만재 기간이 곧 정지 기간은 아니다.
                Duration stopped = isStop(c.state()) ? stoppedByEvent(c.id(), cycle.now()) : Duration.ZERO;
                reading.active().put(c.id(), grade(stopped, cycle));
            } else if (isKnown(c.state())) {
                reading.cleared().add(c.id());
            }
        }
        return reading;
    }

    private Reading scannerReading(Cycle cycle) {
        return switch (PlantStatusService.scannerState(events.latest(SCANNER))) {
            case DEGRADED -> new Reading(Map.of(SCANNER, grade(Duration.ZERO, cycle)), Set.of());
            case RUNNING -> new Reading(Map.of(), Set.of(SCANNER));
            default -> new Reading(Map.of(), Set.of());
        };
    }

    private Reading robotReading(Cycle cycle) {
        List<RobotStatus> known = fleet.all().stream()
                .filter(r -> topology.isRobot(r.id()) && isKnown(r.state()))
                .toList();
        long low = known.stream()
                .filter(r -> r.batteryPercent() < LOW_BATTERY_PERCENT)
                .count();
        if (low >= LOW_BATTERY_ROBOTS) {
            return new Reading(Map.of(FLEET, grade(Duration.ZERO, cycle)), Set.of());
        }
        long unknown = topology.all().stream()
                .filter(topology::isRobot)
                .filter(id -> known.stream().noneMatch(r -> r.id().equals(id)))
                .count();
        // 모르는 로봇이 전부 저전압이어도 기준에 못 미칠 때만 해소로 본다. 한 대가 충전 중이라고 건이 묶이지 않는다.
        return new Reading(Map.of(), low + unknown < LOW_BATTERY_ROBOTS ? Set.of(FLEET) : Set.of());
    }

    private Severity grade(Duration stopped, Cycle cycle) {
        long parcels = cycle.ratePerMin() * stopped.toMinutes();
        return SeverityPolicy.grade(stopped, (int) Math.min(parcels, Integer.MAX_VALUE), cycle.untilCutoff());
    }

    /** 컨베이어 건은 정지로 열렸으므로 열린 뒤로 계속 정지다. 이벤트를 잃거나 코드가 바뀌어도 그 시간이 줄지 않는다. */
    private Duration conveyorStopped(String id, Cycle cycle) {
        Duration byEvent = stoppedByEvent(id, cycle.now());
        Duration sinceOpened = cycle.open().stream()
                .filter(i -> i.kind().equals(CONVEYOR_STOPPED) && i.equipmentId().equals(id))
                .findFirst()
                .map(i -> Duration.between(i.openedAt(), cycle.now()))
                .orElse(Duration.ZERO);
        return sinceOpened.compareTo(byEvent) > 0 ? sinceOpened : byEvent;
    }

    private Duration stoppedByEvent(String id, Instant now) {
        return events.latest(id)
                .filter(e -> STOP_CODES.contains(e.code()))
                .map(e -> Duration.between(e.occurredAt(), now))
                // 게이트웨이 시계가 앞서면 음수가 된다. 등급 계산이 거부해 주기 전체가 멈추지 않게 한다.
                .filter(Duration::isPositive)
                .orElse(Duration.ZERO);
    }

    private void reconcile(String kind, Supplier<Reading> read, List<Incident> open, Instant now) {
        try {
            Reading reading = read.get();
            List<Incident> ofKind =
                    open.stream().filter(i -> i.kind().equals(kind)).toList();
            reading.active().forEach((id, severity) -> {
                Optional<Incident> existing = ofKind.stream()
                        .filter(i -> i.equipmentId().equals(id))
                        .findFirst();
                if (existing.isEmpty()) {
                    incidents.open(new Incident(UUID.randomUUID(), id, kind, severity, now, null));
                } else if (severity.compareTo(existing.get().severity()) < 0) {
                    incidents.regrade(existing.get().id(), severity);
                }
            });
            ofKind.stream()
                    .filter(i -> reading.cleared().contains(i.equipmentId()))
                    .forEach(i -> incidents.close(i.id(), now));
        } catch (RuntimeException e) {
            logSkipped(kind, e);
        }
    }

    private static boolean isStop(RunState state) {
        return state == RunState.STOPPED || state == RunState.FAULT;
    }

    private static boolean isKnown(RunState state) {
        return state != RunState.UNKNOWN && state != RunState.OFFLINE;
    }

    private static void logSkipped(String kind, RuntimeException e) {
        // 예외 메시지에 설비 응답이 섞일 수 있어 종류만 남긴다.
        log.log(Level.WARNING, "anomaly detection skipped " + kind + ": " + e.getClass().getName());
    }
}
